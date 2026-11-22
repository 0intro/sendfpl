import XCTest

/// The app on a simulator, sending to the navigator in memory, in each language, with a screenshot
/// of every screen, then handed files as a pick hands them: a route to import and send, and a file
/// that is no credential. A launch without -demo reaches that navigator from About, as App Review
/// does. Run with TEST_RUNNER_SCREENSHOTS set to a directory, xcodebuild
/// hands the test SCREENSHOTS, and the screenshots are written there as well as attached to the
/// result. Each failure adds a screenshot of its own and prints the element tree. With
/// TEST_RUNNER_VERSION, the About page must show that version. A pick returns the file the launch
/// names with -pick, in place of the system's picker, whose file provider on CI's simulators has
/// lost files it listed.
final class SendFPLUITests: XCTestCase {
    private struct Words {
        let language: String
        let locale: String
        let send: String
        let sent: String
        let about: String
        let log: String
        let back: String
        let logTitle: String
        let navigators: String
        let tryDemo: String
    }

    private let languages = [
        Words(language: "en", locale: "en_US", send: "Send to navigator", sent: "Sent", about: "About",
              log: "Log", back: "Back", logTitle: "Protocol log", navigators: "Navigators",
              tryDemo: "Try the demo navigator"),
        Words(language: "fr", locale: "fr_FR", send: "Envoyer au navigateur", sent: "Envoyé", about: "À propos",
              log: "Journal", back: "Retour", logTitle: "Journal du protocole", navigators: "Navigateurs",
              tryDemo: "Essayer le navigateur de démonstration"),
    ]

    /// A route as a sendfpl: link carries it, the way another app hands one over.
    private let route = URL(string: "sendfpl://send?route=LFPL%20LOBEL,N48475E002522%20PMN%20LFPK")!

    /// The app the test drives, whose screen a failure records.
    private var app: XCUIApplication?

    override func setUp() {
        continueAfterFailure = false
    }

    /// A failure with the screen it left, attached to it and written beside the screenshots, and
    /// the element tree printed, so a run says what the test saw.
    override func record(_ issue: XCTIssue) {
        var issue = issue
        if let app = app {
            let screenshot = app.screenshot()
            issue.add(XCTAttachment(screenshot: screenshot))
            let test = name.components(separatedBy: " ").last?.trimmingCharacters(in: CharacterSet(charactersIn: "]")) ?? name
            save(screenshot, "failed-\(test)")
            print(app.debugDescription)
        }
        super.record(issue)
    }

    func testSendToTheNavigatorInMemory() throws {
        for w in languages {
            let app = launch(w)
            shot(app, "\(w.language)-home")

            app.open(route)
            let send = app.buttons[w.send]
            XCTAssertTrue(send.waitForExistence(timeout: 10), "\(w.language): no Send button")
            XCTAssertTrue(wait(for: send, enabled: true), "\(w.language): the route did not enable Send")
            shot(app, "\(w.language)-route")

            send.tap()
            if !app.staticTexts[w.sent].waitForExistence(timeout: 30) {
                explain(app, w)
                XCTFail("\(w.language): nothing was sent")
            }
            shot(app, "\(w.language)-sent")

            app.buttons[w.log].tap()
            XCTAssertTrue(app.staticTexts[w.logTitle].waitForExistence(timeout: 10), "\(w.language): no log")
            shot(app, "\(w.language)-log")
            dismissSheet(app)

            // The chip's label also carries its icon's description once the navigator has answered.
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "GPS 175")).firstMatch.tap()
            XCTAssertTrue(app.staticTexts[w.navigators].waitForExistence(timeout: 10),
                          "\(w.language): no navigator sheet")
            shot(app, "\(w.language)-navigators")
            dismissSheet(app)

            app.buttons[w.about].tap()
            XCTAssertTrue(app.buttons[w.back].waitForExistence(timeout: 10), "\(w.language): no About page")
            if let version = ProcessInfo.processInfo.environment["VERSION"] {
                XCTAssertTrue(app.staticTexts["SendFPL \(version)"].exists, "\(w.language): no version \(version)")
            }
            shot(app, "\(w.language)-about")
            // The navigator in memory is the one Send aims at already, so the button leads home.
            let tryDemo = app.buttons[w.tryDemo]
            XCTAssertTrue(reveal(app, tryDemo, timeout: 15), "\(w.language): no button to try the demo")
            shot(app, "\(w.language)-about-demo")
            tryDemo.tap()
            XCTAssertTrue(app.buttons[w.send].waitForExistence(timeout: 10),
                          "\(w.language): trying the demo left About open")

            app.terminate()
        }
    }

    /// A launch as a pilot's or App Review's, without -demo: About's button lists the navigator in
    /// memory and aims Send at it. Send needs a credential, and CI's keychain starts empty, so the
    /// -demo tests do the sending.
    func testTryTheDemoFromAbout() throws {
        let w = languages[0]
        let app = launch(w, demo: false)
        app.buttons[w.about].tap()
        let tryDemo = app.buttons[w.tryDemo]
        XCTAssertTrue(reveal(app, tryDemo, timeout: 15), "no button to try the demo")
        tryDemo.tap()
        let chip = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "GPS 175 DEMO")).firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 10), "Send does not aim at the demo navigator")
        shot(app, "\(w.language)-demo-from-about")
        app.terminate()
    }

    /// A route file picked is imported and sent, as a pilot opens a file saved from a planner. The
    /// copy a pick hands the app goes once it is read, which the workflow checks in the app's
    /// container after the test.
    func testImportARouteFileAndSendIt() throws {
        let w = languages[0]
        let file = try routeFile()
        let app = launch(w, pick: file)
        let openFile = app.buttons["Open a route file"]
        XCTAssertTrue(openFile.waitForExistence(timeout: 10), "no button to open a route file")
        openFile.tap()
        // The route lands in the box, its ARINC 702A preview under it. Its 22 points push the
        // preview and Send below an iPhone's screen, which leaves them out of the element tree
        // until they are scrolled to.
        let preview = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "FPN/")).firstMatch
        if !reveal(app, preview, timeout: 20) {
            explain(app, w)
            XCTFail("the route file was not imported")
        }
        shot(app, "\(w.language)-import-route")
        let send = app.buttons[w.send]
        XCTAssertTrue(reveal(app, send, timeout: 15), "no Send button after the import")
        if !wait(for: send, enabled: true) {
            explain(app, w)
            XCTFail("the imported route did not enable Send")
        }
        send.tap()
        if !reveal(app, app.staticTexts[w.sent], timeout: 30) {
            explain(app, w)
            XCTFail("the imported route was not sent")
        }
        shot(app, "\(w.language)-import-sent")
        app.terminate()
    }

    /// The setup sheet, reached from About as a pilot with a stored credential reaches it, and a
    /// file picked there that is no credential, refused under the name of the field it lacks.
    func testRefuseAFileThatIsNoCredential() throws {
        let w = languages[0]
        let app = launch(w, pick: notACredential)
        app.buttons[w.about].tap()
        let manage = app.buttons["Manage credential"]
        XCTAssertTrue(manage.waitForExistence(timeout: 10), "no Manage credential on About")
        var swipes = 0
        while !manage.isHittable && swipes < 6 {
            app.swipeUp()
            swipes += 1
        }
        manage.tap()
        XCTAssertTrue(app.staticTexts["Connext credential"].waitForExistence(timeout: 10), "no setup sheet")
        shot(app, "\(w.language)-setup")
        let replace = app.buttons.matching(
            NSPredicate(format: "label IN %@", ["Replace credential", "Import credential"] as NSArray)).firstMatch
        XCTAssertTrue(replace.waitForExistence(timeout: 10), "no button to import a credential")
        replace.tap()
        let back = app.buttons[w.back]
        if back.waitForExistence(timeout: 10) {
            back.tap()
        }
        let refusal = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "not a usable credential")).firstMatch
        if !refusal.waitForExistence(timeout: 10) {
            explain(app, w)
            XCTFail("the file was not refused as a credential")
        }
        shot(app, "\(w.language)-credential-refused")
        app.terminate()
    }

    /// Whether `element` is on screen within `timeout`, the screen scrolled up a step each second
    /// it is not: a drag from low on the screen to its middle, as a pilot's thumb scrolls.
    private func reveal(_ app: XCUIApplication, _ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists && element.isHittable {
                return true
            }
            let low = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
            low.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
            sleep(1)
        }
        return element.exists && element.isHittable
    }

    /// The app in the language of `w`, with -demo unless `demo` is false, and with `pick` as what a
    /// pick returns: the app writes it where the system's picker leaves its copies.
    private func launch(_ w: Words, demo: Bool = true, pick: (name: String, data: Data)? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = (demo ? ["-demo"] : []) + ["-AppleLanguages", "(\(w.language))", "-AppleLocale", w.locale]
        if let pick = pick {
            app.launchArguments += ["-pick", pick.name]
            app.launchEnvironment["SENDFPL_PICK"] = pick.data.base64EncodedString()
        }
        app.launch()
        self.app = app
        return app
    }

    /// A route file from the route tests' own, which the test target carries.
    private func routeFile() throws -> (name: String, data: Data) {
        let url = try XCTUnwrap(Bundle(for: SendFPLUITests.self).url(forResource: "chateaux", withExtension: "fpl"))
        return ("chateaux.fpl", try Data(contentsOf: url))
    }

    /// A file that is no credential: a user id, and no token or entitlement.
    private let notACredential = (name: "not-a-credential.json", data: Data("{\"user_id\": 1}\n".utf8))

    /// What a failed send left: the screen, then the protocol log when the app offers one, which
    /// the failure that follows records.
    private func explain(_ app: XCUIApplication, _ w: Words) {
        shot(app, "\(w.language)-failed")
        let log = app.buttons[w.log]
        guard log.exists else { return }
        log.tap()
        _ = app.staticTexts[w.logTitle].waitForExistence(timeout: 5)
    }

    private func wait(for element: XCUIElement, enabled: Bool) -> Bool {
        let done = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == %@", NSNumber(value: enabled)), object: element)
        return XCTWaiter().wait(for: [done], timeout: 10) == .completed
    }

    /// A tap on the scrim above an open sheet, which closes it as a pilot's tap does.
    private func dismissSheet(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        sleep(1)
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        save(screenshot, name)
    }

    /// `screenshot` as a PNG in the SCREENSHOTS directory, named for the device and `name`.
    private func save(_ screenshot: XCUIScreenshot, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["SCREENSHOTS"] else { return }
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        let url = URL(fileURLWithPath: dir).appendingPathComponent("\(device)-\(name).png")
        try? screenshot.pngRepresentation.write(to: url)
    }
}
