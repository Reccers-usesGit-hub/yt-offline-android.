import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @AppStorage("quality") private var quality = 1080

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Download quality", selection: $quality) {
                        ForEach(DownloadSheet.qualities, id: \.self) { q in
                            Text(q == 2160 ? "4K" : "\(q)p" + (q == 1080 ? " (recommended)" : "")).tag(q)
                        }
                    }
                } footer: {
                    Text("Videos download at this quality or the closest lower one this device can play.")
                }

                Section("How to download") {
                    Label {
                        Text("In the YouTube app, open a video or playlist, tap **Share**, then **Offline Downloads**. (If you don't see it, scroll the app row to the end, tap **More**, and turn it on.)")
                    } icon: { Image(systemName: "square.and.arrow.up") }
                    Label {
                        Text("Or copy a link and tap **Paste** at the top of Downloads.")
                    } icon: { Image(systemName: "doc.on.clipboard") }
                    Label {
                        Text("Keep the app open while big downloads run. iOS pauses apps soon after you leave them.")
                    } icon: { Image(systemName: "exclamationmark.circle") }
                }

                Section("Storage") {
                    LabeledContent("Downloads", value: "\(store.videos.count) videos · \(Fmt.bytes(store.usedBytes))")
                    LabeledContent("Free on this device", value: Fmt.bytes(store.freeBytes))
                }

                Section {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                } footer: {
                    Text("Videos are kept privately inside this app. They don't appear in Photos or Files, and they're deleted if you delete the app.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
