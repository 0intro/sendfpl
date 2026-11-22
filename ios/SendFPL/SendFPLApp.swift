import SendFPLUI
import SwiftUI

/// The iPhone and iPad app. Everything it shows is Kotlin, in SendFPLUI: this is the window and
/// the door through which iOS hands over a route file or a sendfpl: link.
@main
struct SendFPLApp: App {
    var body: some Scene {
        WindowGroup {
            Screens()
                // Compose lays itself out within the safe area, as on Android edge to edge.
                .ignoresSafeArea()
                .onOpenURL { MainViewControllerKt.openUrl(url: $0) }
        }
    }
}

private struct Screens: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
