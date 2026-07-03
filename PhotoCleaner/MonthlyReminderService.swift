import Foundation
import UserNotifications

@MainActor
final class MonthlyReminderService: NSObject, ObservableObject {
    static let shared = MonthlyReminderService()

    @Published private(set) var isEnabled: Bool
    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined

    private let center = UNUserNotificationCenter.current()

    private static let enabledKey = "photoCleaner.monthlyReminder.enabled.v1"
    private static let minimumPhotoCount = 20
    private let notificationIdentifier = "photoCleaner.monthlyReminder"

    private override init() {
        isEnabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
        super.init()
    }

    func start() {
        center.delegate = self
        Task {
            await refreshAuthorizationStatus()
        }
    }

    func refreshAuthorizationStatus() async {
        let settings = await center.notificationSettings()
        authorizationStatus = settings.authorizationStatus
        if settings.authorizationStatus == .denied, isEnabled {
            isEnabled = false
            UserDefaults.standard.set(false, forKey: Self.enabledKey)
            removePendingReminder()
        }
    }

    func setEnabled(
        _ enabled: Bool,
        monthGroups: [PhotoMonthGroup],
        reviewedCounts: [String: Int]
    ) {
        if enabled {
            Task {
                let granted = await requestAuthorizationIfNeeded()
                guard granted else {
                    await refreshAuthorizationStatus()
                    return
                }
                isEnabled = true
                UserDefaults.standard.set(true, forKey: Self.enabledKey)
                await refreshAuthorizationStatus()
                reconcile(monthGroups: monthGroups, reviewedCounts: reviewedCounts)
            }
        } else {
            isEnabled = false
            UserDefaults.standard.set(false, forKey: Self.enabledKey)
            removePendingReminder()
        }
    }

    func reconcile(
        monthGroups: [PhotoMonthGroup],
        reviewedCounts: [String: Int]
    ) {
        guard isEnabled, authorizationStatus.isAuthorizedForScheduling else {
            removePendingReminder()
            return
        }

        guard let candidate = reminderCandidate(
            monthGroups: monthGroups,
            reviewedCounts: reviewedCounts
        ) else {
            removePendingReminder()
            return
        }

        scheduleReminder(for: candidate.group, deliveryDate: candidate.deliveryDate)
    }

    private func requestAuthorizationIfNeeded() async -> Bool {
        let settings = await center.notificationSettings()
        authorizationStatus = settings.authorizationStatus

        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        case .denied:
            return false
        @unknown default:
            return false
        }
    }

    private func reminderCandidate(
        monthGroups: [PhotoMonthGroup],
        reviewedCounts: [String: Int]
    ) -> (group: PhotoMonthGroup, deliveryDate: Date)? {
        let calendar = Calendar.current
        let now = Date()
        let currentMonthStart = calendar.date(
            from: calendar.dateComponents([.year, .month], from: now)
        ) ?? now
        guard let previousMonthStart = calendar.date(byAdding: .month, value: -1, to: currentMonthStart),
              let group = monthGroups.first(where: { calendar.isDate($0.date, equalTo: previousMonthStart, toGranularity: .month) }) else {
            return nil
        }

        guard group.assets.count >= Self.minimumPhotoCount else { return nil }
        let reviewedCount = reviewedCounts[group.id] ?? 0
        guard reviewedCount < group.availableIDs.count else { return nil }

        return (group, nextReminderDate(from: now))
    }

    private func nextReminderDate(from now: Date) -> Date {
        let calendar = Calendar.current
        if let todayAtEight = calendar.date(bySettingHour: 20, minute: 0, second: 0, of: now),
           todayAtEight > now {
            return todayAtEight
        }

        let tomorrow = calendar.date(byAdding: .day, value: 1, to: now) ?? now.addingTimeInterval(86_400)
        return calendar.date(bySettingHour: 20, minute: 0, second: 0, of: tomorrow)
            ?? tomorrow.addingTimeInterval(60)
    }

    private func scheduleReminder(for group: PhotoMonthGroup, deliveryDate: Date) {
        let content = UNMutableNotificationContent()
        let month = AppLanguageSettings.shared.formatMonthYear(group.date)
        content.title = AppLanguageSettings.shared.string("monthly.reminder.title")
        content.body = String.localizedStringWithFormat(
            AppLanguageSettings.shared.string("monthly.reminder.body.format"),
            month
        )
        content.sound = .default

        let interval = max(deliveryDate.timeIntervalSinceNow, 60)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        let request = UNNotificationRequest(
            identifier: notificationIdentifier,
            content: content,
            trigger: trigger
        )

        center.removePendingNotificationRequests(withIdentifiers: [notificationIdentifier])
        center.add(request)
    }

    private func removePendingReminder() {
        center.removePendingNotificationRequests(withIdentifiers: [notificationIdentifier])
    }
}

extension MonthlyReminderService: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}

private extension UNAuthorizationStatus {
    var isAuthorizedForScheduling: Bool {
        switch self {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined, .denied:
            return false
        @unknown default:
            return false
        }
    }
}
