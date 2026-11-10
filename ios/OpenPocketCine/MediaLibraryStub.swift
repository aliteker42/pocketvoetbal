import AVFoundation
import ImageIO
import MonitorPresentation
import MonitorUI
import OpenCineAppKit
import OpenCineCameraControls
import OpenCineDJIApple
import OpenCineMedia
import OpenPocketViewCore
import SwiftUI
import UIKit

enum MediaClipPresentation {
    static let resolutionLevels = MonitorMediaResolutionLevel.standard.filter { $0.minimumWidth <= 3840 && $0.minimumWidth != 2880 }
}

typealias MediaLibraryCopy = MonitorMediaGalleryCopy

struct MediaLibraryView: View {
    var safeArea: EdgeInsets = .init()
    var onClose: (() -> Void)? = nil
    @Environment(AppModel.self) private var model
    @State private var presented: MonitorMediaGalleryOpen<MediaFile>?
    @State private var deliveryFiles: [MediaFile]?
    private var session: CameraSession { model.session }
    private var library: MonitorMediaLibraryModel<MediaFile> { session.mediaLibrary }
    private var isLive: Bool { if case .live = session.phase { return true }; return false }
    private var operations: MonitorMediaGalleryOperations<MediaFile> {
        if isLive {
            return .init(download: { record in await session.download(file: record.payload) },
                delete: { record in await session.deleteMedia(record.payload) },
                favorite: { record in session.toggleFavorite(record.payload) })
        }
        return .init(favorite: { record in session.toggleFavorite(record.payload) })
    }
    var body: some View {
        MonitorOperatorCatalogScreen(library: library,
            brand: session.connectedCamera?.name ?? "OpenPocketCine", safeArea: safeArea,
            sourceAvailable: isLive, operations: operations,
            colorChoices: ColorMode.allCases.map { .init(id: String($0.rawValue), label: $0.label) },
            resolutionLevels: MediaClipPresentation.resolutionLevels,
            facts: { record in
                let file = session.libraryFile(record)
                return .init(resolution: file.resolution, byteCount: file.sizeBytes,
                    durationSeconds: file.durationSeconds, dateKey: file.dateKey,
                    colorLabel: record.facts.color.flatMap(UInt8.init).flatMap { ColorMode(rawValue: $0) }?.label, progress: session.mediaDownloadProgress[file.path])
            }, begin: { session.beginMediaBrowse() }, refresh: { session.refreshMedia() }, close: dismiss,
            open: { presented = $0 }, share: { records, token in
                guard library.isCurrent(token) else { return }
                deliveryFiles = records.map { record in session.libraryFile(record) }
            }, cachingActive: { !(session.mediaLibraryOwner?.hostProgress.isEmpty ?? true) },
            thumbnail: { context in
                let record = context.record
                let file = session.libraryFile(record)
                let grade = MediaCacheGrade.resolve(hasOriginal: context.cached?.originalURL != nil,
                    hasProxy: context.cached?.proxyURL != nil)
                MonitorMediaCatalogThumbnail(cameraID: session.mediaCameraID, assetID: DjiAppleMediaAssets.asset(for: file).id.rawValue, file: file,
                    cacheGrade: grade, localURL: context.cached?.originalURL, thumbnailURL: context.cached?.thumbnailURL,
                    isCurrent: context.isCurrent, onDuration: context.duration, source: { model.session })
            })
            .fullScreenCover(item: $presented) { request in
                if (request.record.facts.presentationKind ?? request.record.asset.kind) == .photo {
                    MediaPhotoViewer(opening: request, library: library, session: session).environment(model)
                } else {
                    MediaPlayerView(opening: request).environment(model)
                }
            }
            .overlay { MonitorOperatorDeliveryChrome(model: model.delivery.lifetime, scope: .surface, remoteDisplayName: "Frame.io") }
            .overlay {
                if let deliveryFiles {
                    MediaDeliveryPopupOverlay(files: deliveryFiles) { self.deliveryFiles = nil }
                }
            }
            .onChange(of: library.lifetimeToken) { _, _ in deliveryFiles = nil }
            #if DEBUG && targetEnvironment(simulator)
                .task(id: library.displayedRecords.first?.asset.id) {
                    // `ReviewLaunch(screen: .playback)`: open the first staged clip as a tap would.
                    guard MonitorUIReview.opensFirstClip, let record = library.displayedRecords.first else { return }
                    MonitorUIReview.opensFirstClip = false
                    presented = .init(record: record, playlist: library.displayedRecords, admission: library.actionToken)
                }
            #endif
    }
    private func dismiss() {
        if let onClose { onClose() } else { model.shell.dismissCover() }
    }
}

/// The shared gallery cell reads this session's cached and camera thumbnails.
extension CameraSession: MonitorMediaThumbnailSource {}

