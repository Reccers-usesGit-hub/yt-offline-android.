import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Shows up as "Offline Downloads" in the share sheet of the YouTube app (and Safari).
/// It shows the download card, then hands the choice to the main app, which does the download.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        Task { @MainActor in
            let text = await sharedText()
            let videoID = YT.videoID(text)
            let listID = YT.listID(text)
            if videoID == nil && listID == nil {
                show(ShareCard { NotALink(onClose: { [weak self] in self?.finish() }) })
            } else {
                show(ShareCard {
                    DownloadSheet(videoID: videoID, listID: listID,
                                  onDownload: { [weak self] r in self?.send(r) },
                                  onClose: { [weak self] in self?.finish() })
                })
            }
        }
    }

    private func show<V: View>(_ root: V) {
        let host = UIHostingController(rootView: root)
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    private func sharedText() async -> String? {
        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        for item in items {
            for p in item.attachments ?? [] {
                if p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let u = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                    return u.absoluteString
                }
                if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                   let s = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                    return s
                }
            }
            if let s = item.attributedContentText?.string, !s.isEmpty { return s }
        }
        return nil
    }

    /// Opens the main app with the request (ytoffline://download?…).
    private func send(_ r: DownloadRequest) {
        UIPasteboard.general.string = r.videoID.map { "https://youtu.be/\($0)" }
            ?? r.listID.map { "https://www.youtube.com/playlist?list=\($0)" }
        var responder: UIResponder? = self
        while let current = responder {
            if let app = current as? UIApplication {
                app.open(r.url, options: [:], completionHandler: nil)
                break
            }
            responder = current.next
        }
        finish()
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }
}

/// Bottom card with a dimmed backdrop, sized for phone and iPad.
private struct ShareCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.25).ignoresSafeArea()
            content
                .frame(maxWidth: 520)
                .background(
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
                        .fill(Color(.systemBackground).opacity(0.55))
                )
                .glass(RoundedRectangle(cornerRadius: 30, style: .continuous))
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
        }
        .tint(.red)
    }
}

private struct NotALink: View {
    let onClose: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.badge.plus").font(.largeTitle).foregroundStyle(.secondary)
            Text("That isn't a YouTube video or playlist link.").font(.headline).multilineTextAlignment(.center)
            Button("Close", action: onClose).glassButton()
        }
        .padding(24)
        .frame(maxWidth: .infinity)
    }
}
