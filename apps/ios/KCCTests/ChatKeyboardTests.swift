import XCTest
@testable import KCC

final class ChatKeyboardTests: XCTestCase {
    func testDockedFloatingAndDismissedKeyboardFrames() {
        let bounds = CGRect(x: 0, y: 0, width: 375, height: 812)
        XCTAssertTrue(ChatKeyboardObserver.coversWindow(frame: CGRect(x: 0, y: 500, width: 375, height: 312), bounds: bounds))
        XCTAssertTrue(ChatKeyboardObserver.coversWindow(frame: CGRect(x: 100, y: 300, width: 200, height: 200), bounds: bounds))
        XCTAssertFalse(ChatKeyboardObserver.coversWindow(frame: CGRect(x: 0, y: 812, width: 375, height: 312), bounds: bounds))
        XCTAssertFalse(ChatKeyboardObserver.coversWindow(frame: CGRect(x: 0, y: 812, width: 375, height: 0), bounds: bounds))
        XCTAssertFalse(ChatKeyboardObserver.coversWindow(frame: .null, bounds: bounds))
        XCTAssertFalse(ChatKeyboardObserver.coversWindow(frame: .infinite, bounds: bounds))
    }
}
