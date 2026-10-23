import AVFoundation
import SwiftUI

@main
struct OfflineDownloadsApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var dl = Downloader.shared
    @StateObject private var sharer = Sharer.shared

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            DownloadsView()
                .environmentObject(store)
                .environmentObject(dl)
                .environmentObject(sharer)
                .tint(.red)
                .overlay(alignment: .top) {
                    if let text = dl.banner {
                        Banner(text: text).padding(.top, 6)
                    }
                }
                .animation(.snappy, value: dl.banner)
                .overlay {
                    if sharer.preparing {
                        VStack(spacing: 10) {
                            ProgressView()
                            Text("Preparing video…").font(.subheadline.weight(.medium))
                        }
                        .padding(22)
                        .glass(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    }
                }
                .sheet(item: $sharer.file) { f in
                    ActivityView(items: [f.url])
                        .presentationDetents([.medium, .large])
                        .ignoresSafeArea()
                }
                // Links from the share extension: ytoffline://download?v=…
                .onOpenURL { url in
                    if let r = DownloadRequest(url: url) { dl.handle(r) }
                }
        }
    }
}
