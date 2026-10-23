import SwiftUI

// MARK: - Glass look (iOS 26 Liquid Glass, with a frosted fallback on older systems)

extension View {
    /// A light glass background in `shape`. Kept subtle on purpose.
    @ViewBuilder
    func glass<S: Shape>(_ shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(Self.makeGlass(tint: tint, interactive: interactive), in: shape)
        } else {
            self
                .background(shape.fill(.ultraThinMaterial))
                .background(shape.fill((tint ?? .clear).opacity(0.35)))
                .overlay(
                    shape.stroke(
                        LinearGradient(colors: [.white.opacity(0.5), .white.opacity(0.08)],
                                       startPoint: .top, endPoint: .bottom),
                        lineWidth: 0.8)
                )
                .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
        }
    }

    @available(iOS 26.0, *)
    private static func makeGlass(tint: Color?, interactive: Bool) -> Glass {
        var g = Glass.regular
        if let tint { g = g.tint(tint) }
        if interactive { g = g.interactive() }
        return g
    }

    /// The main call-to-action button style.
    @ViewBuilder
    func prominentGlassButton() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent)
        } else {
            self.buttonStyle(.borderedProminent)
        }
    }

    @ViewBuilder
    func glassButton() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered)
        }
    }

    /// Soft lift for thumbnails, so cards feel a little 3D without being loud.
    func lifted(_ radius: CGFloat = 12) -> some View {
        self
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(
                        LinearGradient(colors: [.white.opacity(0.28), .white.opacity(0.0)],
                                       startPoint: .top, endPoint: .center),
                        lineWidth: 0.8)
            )
            .shadow(color: .black.opacity(0.18), radius: 8, y: 4)
    }
}

/// Capsule button with icon + label, like YouTube's action row, in glass.
struct GlassPill: View {
    var title: String
    var systemImage: String
    var tint: Color? = nil
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.medium))
                .labelStyle(.titleAndIcon)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glass(Capsule(), tint: tint, interactive: true)
    }
}

/// Filter chip ("All", playlist names).
struct GlassChip: View {
    var title: String
    var selected: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .foregroundStyle(selected ? Color(.systemBackground) : Color.primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(selected ? Capsule().fill(Color.primary) : nil)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glass(Capsule(), interactive: true)
    }
}

/// Small glass badge used on thumbnails (duration, "12 videos").
struct GlassBadge: View {
    var text: String
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage { Image(systemName: systemImage) }
            Text(text)
        }
        .font(.caption.weight(.semibold).monospacedDigit())
        .foregroundStyle(.white)
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .glass(RoundedRectangle(cornerRadius: 6, style: .continuous), tint: .black.opacity(0.55))
        .environment(\.colorScheme, .dark)
    }
}

// MARK: - Formatting

enum Fmt {
    static func duration(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        guard s > 0 else { return "" }
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    static func views(_ n: Int) -> String {
        guard n > 0 else { return "" }
        return compact(n) + (n == 1 ? " view" : " views")
    }

    static func compact(_ n: Int) -> String {
        let d = Double(n)
        switch d {
        case 1_000_000_000...: return trim(d / 1_000_000_000) + "B"
        case 1_000_000...: return trim(d / 1_000_000) + "M"
        case 1_000...: return trim(d / 1_000) + "K"
        default: return String(n)
        }
    }

    private static func trim(_ v: Double) -> String {
        v < 10 ? String(format: "%.1f", v).replacingOccurrences(of: ".0", with: "") : String(Int(v))
    }

    static func bytes(_ b: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: b, countStyle: .file)
    }
}
