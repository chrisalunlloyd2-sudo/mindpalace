const std = @import("std");

/// zig-ember — the smallest alive Zig program: greet, loop, and leave the
/// fire warm for whoever walks in next. A fixture room for the demo mansion:
/// real files, zero auth, zero network.
pub fn greet(name: []const u8) []const u8 {
    return name; // the mansion itself does the greeting
}

pub fn main() void {
    std.debug.print("ember lit\n", .{});
}