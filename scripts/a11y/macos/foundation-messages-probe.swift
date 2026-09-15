import Foundation
import ObjectiveC

// The type encodings of the Foundation messages the macOS bridge SENDS (as opposed to the AppKit
// selectors it installs, which dump-appkit-constants.swift reads): each is sent through a plain
// objc_msgSend whose Java signature is chosen by hand, so the return and argument shapes are facts
// to read off the running Foundation, not to recall. For each message it prints the encoding
// class_getInstanceMethod answers on the class the bridge sends it to, and, as a behaviour check,
// what the message answers for the kinds of object an AX client's write arrives as (a string, an
// integer, a fraction, a boolean, an array of two).
//
// Build with `swiftc -O -o foundation-messages-probe foundation-messages-probe.swift`; run it
// anywhere, no window and no trust needed.
//
// usage: foundation-messages-probe

setvbuf(stdout, nil, _IOLBF, 0)

let messages: [(String, String)] = [
    ("NSObject", "isKindOfClass:"),
    ("NSString", "isEqualToString:"),
    ("NSNumber", "stringValue"),
    ("NSArray", "count"),
    ("NSArray", "objectAtIndex:"),
]

print("==== encodings (class_getInstanceMethod on the class named) ====")
for (className, selector) in messages {
    guard let cls = NSClassFromString(className) else { print("  \(className) !! NO SUCH CLASS"); continue }
    guard let method = class_getInstanceMethod(cls, NSSelectorFromString(selector)) else {
        print("  -[\(className) \(selector)] !! NOT DECLARED"); continue
    }
    let encoding = method_getTypeEncoding(method).map { String(cString: $0) } ?? "<none>"
    print("  -[\(className) \(selector)] \(encoding)")
}

print("\n==== what the messages answer for a client's write ====")
let written: [(String, AnyObject)] = [
    ("string \"60\"", "60" as NSString),
    ("CFNumber 55 (integer)", NSNumber(value: 55)),
    ("CFNumber 55.5 (double)", NSNumber(value: 55.5)),
    ("kCFBooleanTrue", kCFBooleanTrue),
    ("array of two strings", ["a", "b"] as NSArray),
]
for (what, object) in written {
    let isString = object.isKind(of: NSString.self)
    let isNumber = object.isKind(of: NSNumber.self)
    let isArray = object.isKind(of: NSArray.self)
    var line = "  \(what): class=\(NSStringFromClass(type(of: object))) isKindOfClass:NSString=\(isString) NSNumber=\(isNumber) NSArray=\(isArray)"
    if isNumber { line += " stringValue='\((object as! NSNumber).stringValue)'" }
    if isArray {
        let array = object as! NSArray
        line += " count=\(array.count) objectAtIndex:0='\(array.object(at: 0))'"
    }
    print(line)
}
