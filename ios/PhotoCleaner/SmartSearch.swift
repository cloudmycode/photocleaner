import CryptoKit
import Foundation
import Photos
import UIKit

// MARK: - Config

enum SmartSearchConfig {
    static var endpoint: URL? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "SmartSearchEndpoint") as? String,
              !raw.isEmpty else { return nil }
        return URL(string: raw)
    }

    static var enrichEndpoint: URL? {
        guard let search = endpoint else { return nil }
        return search.deletingLastPathComponent().appendingPathComponent("enrich-tags")
    }

    static var deviceID: String {
        UIDevice.current.identifierForVendor?.uuidString ?? "unknown-device"
    }

    static var secret: String {
        Bundle.main.object(forInfoDictionaryKey: "SmartSearchSecret") as? String ?? ""
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    static var buildVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
    }
}

// MARK: - SearchPlan

struct SearchPlan: Decodable {
    var summary: String?
    var filters: SearchFilters?
    var must: SearchMust?
    var count: Int?
    var confidence: Double?
}

struct SearchFilters: Decodable {
    var dateRange: SearchDateRange?
    var locationBounds: [SearchLocationBound]?
    var mediaTypes: [String]?
    var assetTypes: [String]?
    var minSizeMB: Double?
    var maxSizeMB: Double?
    var minPixelWidth: Int?
    var minPixelHeight: Int?
    var hasLocation: Bool?
}

struct SearchMust: Decodable {
    var tagGroups: [[String]]? = nil
    var textGroups: [[String]]? = nil
}

struct SearchDateRange: Decodable {
    var start: String?
    var end: String?
}

struct SearchLocationBound: Decodable {
    var name: String?
    var minLatitude: Double?
    var maxLatitude: Double?
    var minLongitude: Double?
    var maxLongitude: Double?
}

enum SmartSearchError: Error {
    case configurationMissing
    case unauthorized
    case network
}

// MARK: - Client

enum SmartSearchClient {
    static let availableTagsLimit = 1000
    static let availableTextHintsLimit = 400

    static func fetchPlan(
        query: String,
        availableTags: [String],
        availableTextHints: [String]
    ) async throws -> SearchPlan {
        guard let url = SmartSearchConfig.endpoint, !SmartSearchConfig.secret.isEmpty else {
            throw SmartSearchError.configurationMissing
        }

        let trimmed = normalizedTransportQuery(query)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(RequestBody(
            query: trimmed,
            locale: normalizedLocaleIdentifier(),
            appVersion: SmartSearchConfig.appVersion,
            buildVersion: SmartSearchConfig.buildVersion,
            availableTags: normalizedAvailableTags(availableTags),
            availableTextHints: normalizedAvailableTextHints(availableTextHints),
            sign: md5Sign(trimmed + SmartSearchConfig.secret)
        ))

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SmartSearchError.network }
        if http.statusCode == 401 { throw SmartSearchError.unauthorized }
        guard (200..<300).contains(http.statusCode) else { throw SmartSearchError.network }

        return try JSONDecoder().decode(SearchPlan.self, from: data)
    }

    private struct RequestBody: Encodable {
        let query: String
        let locale: String
        let appVersion: String
        let buildVersion: String
        let availableTags: [String]
        let availableTextHints: [String]
        let sign: String
    }

    private static func md5Sign(_ raw: String) -> String {
        Insecure.MD5.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func normalizedTransportQuery(_ query: String) -> String {
        query
            .replacingOccurrences(of: "\u{3000}", with: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
    }

    private static func normalizedLocaleIdentifier() -> String {
        let identifier = Locale.autoupdatingCurrent.identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        return identifier.isEmpty ? "und" : identifier
    }

    private static func normalizedAvailableTags(_ availableTags: [String]) -> [String] {
        var seen = Set<String>()
        var normalized: [String] = []
        for tag in availableTags {
            let trimmed = tag.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !trimmed.isEmpty, seen.insert(trimmed).inserted else { continue }
            normalized.append(trimmed)
            if normalized.count >= availableTagsLimit { break }
        }
        return normalized
    }

    private static func normalizedAvailableTextHints(_ hints: [String]) -> [String] {
        var seen = Set<String>()
        var normalized: [String] = []
        for hint in hints {
            let trimmed = hint
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\u{3000}", with: " ")
                .split(whereSeparator: \.isWhitespace)
                .joined(separator: " ")
            guard !trimmed.isEmpty else { continue }

            let dedupeKey = trimmed.lowercased()
            guard seen.insert(dedupeKey).inserted else { continue }
            normalized.append(trimmed)
            if normalized.count >= availableTextHintsLimit { break }
        }
        return normalized
    }
}

// MARK: - Tag Enrichment

enum TagEnrichmentClient {
    static let ocrSnippetLimit = 400

    struct Payload: Encodable {
        let assetId: String
        let rawTags: [String]
        let ocrSnippet: String
        let mediaType: String
        let assetTypes: [String]
    }

    struct ResultItem: Decodable {
        let assetId: String
        let tags: [String]
        let sensitiveTypes: [String]
        let searchText: String?
    }

    static func enrich(payloads: [Payload]) async throws -> [ResultItem] {
        guard let url = SmartSearchConfig.enrichEndpoint, !SmartSearchConfig.secret.isEmpty else {
            throw SmartSearchError.configurationMissing
        }
        guard !payloads.isEmpty else { return [] }

        let deviceID = SmartSearchConfig.deviceID
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(RequestBody(
            deviceId: deviceID,
            locale: Locale.current.identifier,
            appVersion: SmartSearchConfig.appVersion,
            buildVersion: SmartSearchConfig.buildVersion,
            items: payloads,
            sign: md5Sign(deviceID + SmartSearchConfig.secret)
        ))

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SmartSearchError.network }
        if http.statusCode == 401 { throw SmartSearchError.unauthorized }
        guard (200..<300).contains(http.statusCode) else {
            throw SmartSearchError.network
        }

        let decoded = try JSONDecoder().decode(ResponseBody.self, from: data)
        return decoded.items
    }

    private struct RequestBody: Encodable {
        let deviceId: String
        let locale: String
        let appVersion: String
        let buildVersion: String
        let items: [Payload]
        let sign: String
    }

    private struct ResponseBody: Decodable {
        let items: [ResultItem]
    }

    private static func md5Sign(_ raw: String) -> String {
        Insecure.MD5.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Service

enum SmartSearchService {
    static func search(query: String) async throws -> [PHAsset] {
        let store = PhotoSearchIndexStore.shared
        let availableTags = await store.topVisualTags()
        let availableTextHints = await store.topSearchTextHints()
        let plan = try await SmartSearchClient.fetchPlan(
            query: query,
            availableTags: availableTags,
            availableTextHints: availableTextHints
        )
        let index = await store.searchIndex()

        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let fetchResult = PHAsset.fetchAssets(with: options)
        var assets: [PHAsset] = []
        fetchResult.enumerateObjects { asset, _, _ in assets.append(asset) }

        let entries = await store.validEntries(for: assets)
        let matched = SearchEngine.match(
            plan: plan,
            entries: entries,
            tagPostings: index.tagPostings
        )
        let matchedIDs = Set(matched.map(\.id))
        return assets.filter { matchedIDs.contains($0.localIdentifier) }
    }
}
