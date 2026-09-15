import AppKit
import Foundation
import ObjectiveC

// The type encodings of the Foundation messages the macOS bridge SENDS (as opposed to the AppKit
// selectors it installs, which dump-appkit-constants.swift reads): each is sent through a plain
// objc_msgSend whose Java signature is chosen by hand, so the return and argument shapes are facts
// to read off the running Foundation, not to recall. For each message it prints the encoding
// class_getInstanceMethod answers on the class the bridge sends it to, and, as a behaviour check,
// what the message answers for the kinds of object an AX client's write arrives as (a string, an
// integer, a fraction, a boolean, an array of two). Class messages (a factory sent to the class
// object) are read with class_getClassMethod in a section of their own; the dictionary an
// announcement's user info is built as is then built through those messages and printed.
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
    // Added 2026-09-15 (macos-C, MACOS-NEW-3): an announcement's user info, and the window a view is in.
    ("NSMutableDictionary", "setObject:forKey:"),
    ("NSView", "window"),
]

let classMessages: [(String, String)] = [
    ("NSMutableDictionary", "dictionary"),
    ("NSNumber", "numberWithInteger:"),
    ("NSString", "stringWithUTF8String:"),
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

print("\n==== class messages (class_getClassMethod on the class named) ====")
for (className, selector) in classMessages {
    guard let cls = NSClassFromString(className) else { print("  \(className) !! NO SUCH CLASS"); continue }
    guard let method = class_getClassMethod(cls, NSSelectorFromString(selector)) else {
        print("  +[\(className) \(selector)] !! NOT DECLARED"); continue
    }
    let encoding = method_getTypeEncoding(method).map { String(cString: $0) } ?? "<none>"
    print("  +[\(className) \(selector)] \(encoding)")
}

print("\n==== an announcement's user info, built through those messages ====")
let info = NSMutableDictionary()
info.setObject("Saved" as NSString, forKey: NSAccessibility.NotificationUserInfoKey.announcement.rawValue as NSString)
info.setObject(NSNumber(value: 90), forKey: NSAccessibility.NotificationUserInfoKey.priority.rawValue as NSString)
for key in (info.allKeys as! [String]).sorted() {
    let value = info.object(forKey: key)!
    print("  \(key) = \(value) (\(NSStringFromClass(type(of: value as AnyObject))))")
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
