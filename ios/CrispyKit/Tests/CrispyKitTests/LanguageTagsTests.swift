import XCTest
@testable import CrispyKit

/// The language vocabulary: what a track's tag becomes when it is stored, and
/// what it reads as when it is shown.
final class LanguageTagsTests: XCTestCase {
    func testAThreeLetterTagBecomesTwoLetters() {
        XCTAssertEqual(LanguageTags.normalize("eng"), "en")
        XCTAssertEqual(LanguageTags.normalize("fre"), "fr")
        XCTAssertEqual(LanguageTags.normalize("ger"), "de")
        XCTAssertEqual(LanguageTags.normalize("ice"), "is")
        XCTAssertEqual(LanguageTags.normalize("cmn"), "zh")
    }

    func testNormalizingTrimsAndIgnoresCase() {
        XCTAssertEqual(LanguageTags.normalize("ENG"), "en")
        XCTAssertEqual(LanguageTags.normalize("  spa\n"), "es")
    }

    func testATwoLetterCodeIsKeptAsItIs() {
        XCTAssertEqual(LanguageTags.normalize("en"), "en")
        XCTAssertEqual(LanguageTags.normalize("pt"), "pt")
    }

    func testAnUnknownTagIsKeptAndABlankOneBecomesUndetermined() {
        XCTAssertEqual(LanguageTags.normalize("xyz"), "xyz")
        XCTAssertEqual(LanguageTags.normalize(nil), "und")
        XCTAssertEqual(LanguageTags.normalize("   "), "und")
    }

    func testAKeywordGetsItsOwnLabel() {
        XCTAssertEqual(LanguageTags.label(for: "none"), "Off")
        XCTAssertEqual(LanguageTags.label(for: "off"), "Off")
        XCTAssertEqual(LanguageTags.label(for: "forced"), "Forced")
        XCTAssertEqual(LanguageTags.label(for: "default"), "Default")
        XCTAssertEqual(LanguageTags.label(for: "device"), "Device language")
        XCTAssertEqual(LanguageTags.label(for: "original"), "Original")
        XCTAssertEqual(LanguageTags.label(for: "und"), "Undetermined")
    }

    func testATagReadsAsItsLanguageName() {
        XCTAssertEqual(LanguageTags.label(for: "en"), "English")
        XCTAssertEqual(LanguageTags.label(for: "eng"), "English")
        XCTAssertEqual(LanguageTags.label(for: "spa"), "Spanish")
    }

    func testATagWithNoNameReadsAsItsUppercasedCode() {
        XCTAssertEqual(LanguageTags.label(for: "zz"), "ZZ")
        XCTAssertEqual(LanguageTags.label(for: nil), "Unknown")
        XCTAssertEqual(LanguageTags.label(for: "  "), "Unknown")
    }

    func testThePickerOffersEveryLanguageOnceInNameOrder() {
        let options = LanguageTags.selectableLanguages
        let codes = options.map(\.code)
        XCTAssertEqual(Set(codes).count, codes.count, "a language is offered more than once")
        XCTAssertTrue(codes.contains("en"))

        let names = options.map(\.name)
        let sorted = names.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        XCTAssertEqual(names, sorted)
    }
}
