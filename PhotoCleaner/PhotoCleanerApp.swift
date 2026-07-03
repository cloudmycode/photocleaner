import Foundation
import ObjectiveC
import SwiftUI

private var languageBundleKey: UInt8 = 0

private final class AppLocalizedBundle: Bundle, @unchecked Sendable {
    override func localizedString(forKey key: String, value: String?, table tableName: String?) -> String {
        if let bundle = objc_getAssociatedObject(self, &languageBundleKey) as? Bundle {
            return bundle.localizedString(forKey: key, value: value, table: tableName)
        }
        return super.localizedString(forKey: key, value: value, table: tableName)
    }
}

@main
struct PhotoCleanerApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var photoLibrary = PhotoLibraryService()
    @StateObject private var monthlyReminder = MonthlyReminderService.shared
    @State private var languageSettings = AppLanguageSettings.shared

    init() {
        AppLanguageSettings.shared.applyLocalizationBundle()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(photoLibrary)
                .environment(\.locale, languageSettings.locale)
                .environment(\.layoutDirection, languageSettings.layoutDirection)
                .environment(languageSettings)
                .task {
                    monthlyReminder.start()
                    photoLibrary.start()
                }
                .onChange(of: scenePhase) {
                    if scenePhase == .active {
                        Task {
                            await monthlyReminder.refreshAuthorizationStatus()
                            monthlyReminder.reconcile(
                                monthGroups: photoLibrary.monthGroups,
                                reviewedCounts: photoLibrary.monthlyReviewedCounts
                            )
                        }
                        photoLibrary.refreshAuthorizationStatus()
                    }
                }
        }
    }
}

@Observable
final class AppLanguageSettings {
    static let shared = AppLanguageSettings()

    enum Option: String, CaseIterable, Identifiable {
        case system
        case english = "en"
        case simplifiedChinese = "zh-Hans"
        case japanese = "ja"
        case french = "fr"
        case korean = "ko"
        case vietnamese = "vi"
        case thai = "th"
        case dutch = "nl"
        case russian = "ru"
        case german = "de"
        case italian = "it"
        case portugueseBrazil = "pt-BR"
        case spanish = "es"
        case persian = "fa"
        case turkish = "tr"
        case polish = "pl"
        case hindi = "hi"
        case ukrainian = "uk"
        case romanian = "ro"
        case quechua = "qu"
        case indonesian = "id"
        case arabic = "ar"

        var id: String { rawValue }

        var localeIdentifier: String? {
            switch self {
            case .system:
                nil
            case .english:
                "en"
            case .simplifiedChinese:
                "zh-Hans"
            case .japanese:
                "ja"
            case .french:
                "fr"
            case .korean:
                "ko"
            case .vietnamese:
                "vi"
            case .thai:
                "th"
            case .dutch:
                "nl"
            case .russian:
                "ru"
            case .german:
                "de"
            case .italian:
                "it"
            case .portugueseBrazil:
                "pt-BR"
            case .spanish:
                "es"
            case .persian:
                "fa"
            case .turkish:
                "tr"
            case .polish:
                "pl"
            case .hindi:
                "hi"
            case .ukrainian:
                "uk"
            case .romanian:
                "ro"
            case .quechua:
                "qu"
            case .indonesian:
                "id"
            case .arabic:
                "ar"
            }
        }

        var nativeDisplayName: String {
            switch self {
            case .system:
                ""
            case .english:
                "English"
            case .simplifiedChinese:
                "简体中文"
            case .japanese:
                "日本語"
            case .french:
                "Français"
            case .korean:
                "한국어"
            case .vietnamese:
                "Tiếng Việt"
            case .thai:
                "ไทย"
            case .dutch:
                "Nederlands"
            case .russian:
                "Русский"
            case .german:
                "Deutsch"
            case .italian:
                "Italiano"
            case .portugueseBrazil:
                "Português (Brasil)"
            case .spanish:
                "Español"
            case .persian:
                "فارسی"
            case .turkish:
                "Türkçe"
            case .polish:
                "Polski"
            case .hindi:
                "हिन्दी"
            case .ukrainian:
                "Українська"
            case .romanian:
                "Română"
            case .quechua:
                "Runa Simi"
            case .indonesian:
                "Bahasa Indonesia"
            case .arabic:
                "العربية"
            }
        }
    }

    private let storageKey = "app.language.preference"

    var selection: Option {
        didSet {
            guard selection != oldValue else { return }
            UserDefaults.standard.set(selection.rawValue, forKey: storageKey)
            applyLocalizationBundle()
        }
    }

    var locale: Locale {
        Locale(identifier: effectiveLocaleIdentifier)
    }

    var layoutDirection: LayoutDirection {
        switch effectiveLocaleIdentifier {
        case "ar", "fa":
            .rightToLeft
        default:
            .leftToRight
        }
    }

    var effectiveLocaleIdentifier: String {
        if let localeIdentifier = selection.localeIdentifier {
            return localeIdentifier
        }
        return Self.resolvedSystemLocaleIdentifier()
    }

    var currentDisplayName: String {
        switch selection {
        case .system:
            Self.option(for: Self.resolvedSystemLocaleIdentifier()).nativeDisplayName
        default:
            selection.nativeDisplayName
        }
    }

    private init() {
        if let raw = UserDefaults.standard.string(forKey: storageKey),
           let option = Option(rawValue: raw) {
            selection = option
        } else {
            selection = .system
        }
        applyLocalizationBundle()
    }

    func applyLocalizationBundle() {
        Self.installBundleHookIfNeeded()
        let identifier = effectiveLocaleIdentifier
        guard let path = Bundle.main.path(forResource: identifier, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            objc_setAssociatedObject(
                Bundle.main,
                &languageBundleKey,
                nil,
                .OBJC_ASSOCIATION_RETAIN_NONATOMIC
            )
            return
        }
        objc_setAssociatedObject(
            Bundle.main,
            &languageBundleKey,
            bundle,
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
    }

    private static var didInstallBundleHook = false

    private static func installBundleHookIfNeeded() {
        guard !didInstallBundleHook else { return }
        object_setClass(Bundle.main, AppLocalizedBundle.self)
        didInstallBundleHook = true
    }

    static func resolvedSystemLocaleIdentifier() -> String {
        for language in Locale.preferredLanguages {
            if language.hasPrefix("zh") { return "zh-Hans" }
            if language.hasPrefix("ja") { return "ja" }
            if language.hasPrefix("fr") { return "fr" }
            if language.hasPrefix("ko") { return "ko" }
            if language.hasPrefix("vi") { return "vi" }
            if language.hasPrefix("th") { return "th" }
            if language.hasPrefix("nl") { return "nl" }
            if language.hasPrefix("ru") { return "ru" }
            if language.hasPrefix("de") { return "de" }
            if language.hasPrefix("it") { return "it" }
            if language.hasPrefix("pt") { return "pt-BR" }
            if language.hasPrefix("es") { return "es" }
            if language.hasPrefix("fa") { return "fa" }
            if language.hasPrefix("tr") { return "tr" }
            if language.hasPrefix("pl") { return "pl" }
            if language.hasPrefix("hi") { return "hi" }
            if language.hasPrefix("uk") { return "uk" }
            if language.hasPrefix("ro") { return "ro" }
            if language.hasPrefix("qu") { return "qu" }
            if language.hasPrefix("id") { return "id" }
            if language.hasPrefix("ar") { return "ar" }
            if language.hasPrefix("en") { return "en" }
        }
        return "en"
    }

    static func option(for localeIdentifier: String) -> Option {
        if localeIdentifier.hasPrefix("zh") { return .simplifiedChinese }
        if localeIdentifier.hasPrefix("ja") { return .japanese }
        if localeIdentifier.hasPrefix("fr") { return .french }
        if localeIdentifier.hasPrefix("ko") { return .korean }
        if localeIdentifier.hasPrefix("vi") { return .vietnamese }
        if localeIdentifier.hasPrefix("th") { return .thai }
        if localeIdentifier.hasPrefix("nl") { return .dutch }
        if localeIdentifier.hasPrefix("ru") { return .russian }
        if localeIdentifier.hasPrefix("de") { return .german }
        if localeIdentifier.hasPrefix("it") { return .italian }
        if localeIdentifier.hasPrefix("pt") { return .portugueseBrazil }
        if localeIdentifier.hasPrefix("es") { return .spanish }
        if localeIdentifier.hasPrefix("fa") { return .persian }
        if localeIdentifier.hasPrefix("tr") { return .turkish }
        if localeIdentifier.hasPrefix("pl") { return .polish }
        if localeIdentifier.hasPrefix("hi") { return .hindi }
        if localeIdentifier.hasPrefix("uk") { return .ukrainian }
        if localeIdentifier.hasPrefix("ro") { return .romanian }
        if localeIdentifier.hasPrefix("qu") { return .quechua }
        if localeIdentifier.hasPrefix("id") { return .indonesian }
        if localeIdentifier.hasPrefix("ar") { return .arabic }
        return .english
    }

    func string(_ key: String) -> String {
        let identifier = effectiveLocaleIdentifier
        let englishBundle = Bundle.main.path(forResource: "en", ofType: "lproj")
            .flatMap(Bundle.init(path:))

        guard let path = Bundle.main.path(forResource: identifier, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            return englishBundle?.localizedString(forKey: key, value: nil, table: nil) ?? key
        }
        let localized = bundle.localizedString(forKey: key, value: nil, table: nil)
        if localized != key {
            return localized
        }
        return englishBundle?.localizedString(forKey: key, value: nil, table: nil) ?? key
    }

    func formatMonth(_ date: Date) -> String {
        date.formatted(.dateTime.month(.wide).locale(locale))
    }

    func formatMonthYear(_ date: Date) -> String {
        date.formatted(.dateTime.year().month(.wide).locale(locale))
    }
}
