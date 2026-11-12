import XCTest

final class FriendshipUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }
    @MainActor
    private func requireStatus(_ fragment: String, in app: XCUIApplication) {
        let status = app.staticTexts["friendship.status"]
        reveal(status, in: app)
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", fragment), object: status)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    }
    @MainActor
    func testRussianSetupCancelAndPersistenceOnFreshSimulator() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU"]
        app.launch()
        let launchScreen = XCTAttachment(screenshot: app.screenshot())
        launchScreen.name = "friendship-launch"
        launchScreen.lifetime = .keepAlways
        add(launchScreen)
        let node = app.staticTexts["identity.nodeId"], fingerprint = app.staticTexts["identity.fingerprint"]
        reveal(node, in: app)
        XCTAssertTrue(node.waitForExistence(timeout: 10))
        let expectedNode = node.label, expectedFingerprint = fingerprint.label
        XCTAssertFalse(expectedNode.isEmpty); XCTAssertEqual(expectedFingerprint.count, 64)
        let setup = app.buttons["friendship.setup"]
        reveal(setup, in: app)
        setup.tap()
        let alert = app.alerts["Создать защищённый список друзей?"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["Отмена"].tap()
        app.terminate(); app.launch()
        reveal(node, in: app)
        XCTAssertTrue(node.waitForExistence(timeout: 10))
        XCTAssertEqual(node.label, expectedNode); XCTAssertEqual(fingerprint.label, expectedFingerprint)
        reveal(setup, in: app)
        XCTAssertTrue(setup.exists)
        setup.tap()
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["Создать список"].tap()
        requireStatus("создан и проверен", in: app)
        XCTAssertFalse(setup.exists)
        XCTAssertTrue(app.staticTexts["Список друзей пока пуст"].exists)
        app.terminate(); app.launch()
        reveal(node, in: app)
        XCTAssertTrue(node.waitForExistence(timeout: 10))
        XCTAssertEqual(node.label, expectedNode); XCTAssertEqual(fingerprint.label, expectedFingerprint)
        requireStatus("список прочитан", in: app)
        XCTAssertFalse(setup.exists)
        XCTAssertTrue(app.staticTexts["Список друзей пока пуст"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "friendship-ready"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
