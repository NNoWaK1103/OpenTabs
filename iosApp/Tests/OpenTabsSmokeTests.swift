import XCTest

final class OpenTabsSmokeTests: XCTestCase {
    func testSharedExerciseOpensInNativeViewer() {
        let app = XCUIApplication()
        app.launch()
        let exercise = app.buttons["First steps"]
        XCTAssertTrue(exercise.waitForExistence(timeout: 15))
        exercise.tap()
        let text = app.staticTexts["tabText"]
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        XCTAssertTrue(text.label.contains("FIRST STEPS - OpenTabs original exercise"))
        XCTAssertTrue(text.label.contains("e|"))
        app.buttons["Larger"].tap()
        XCTAssertTrue(text.exists)
    }
}
