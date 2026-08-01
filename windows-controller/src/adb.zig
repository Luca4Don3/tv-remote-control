const std = @import("std");

pub const process_supervisor = @import("process_supervisor.zig");
pub const manager = @import("adb_manager.zig");

pub const Version = struct {
    major: u32,
    minor: u32,
    patch: u32,

    pub fn atLeast(self: Version, required: Version) bool {
        if (self.major != required.major) return self.major > required.major;
        if (self.minor != required.minor) return self.minor > required.minor;
        return self.patch >= required.patch;
    }

    pub fn newerThan(self: Version, other: Version) bool {
        return self.atLeast(other) and !self.eql(other);
    }

    pub fn eql(self: Version, other: Version) bool {
        return self.major == other.major and self.minor == other.minor and self.patch == other.patch;
    }
};

pub const RepositoryChecksumAlgorithm = enum {
    sha1,
    sha256,
};

pub const RepositoryArchive = struct {
    relative_url: []const u8,
    size: u64,
    checksum: []const u8,
    checksum_algorithm: RepositoryChecksumAlgorithm,
};

pub const RepositoryPackage = struct {
    version: Version,
    archive: RepositoryArchive,
};

pub const AdbInfo = struct {
    protocol: Version,
    platform_tools: ?Version,
};

pub const minimum_protocol = Version{ .major = 1, .minor = 0, .patch = 41 };
pub const minimum_platform_tools = Version{ .major = 30, .minor = 0, .patch = 0 };

pub const DependencyAction = enum {
    use_existing,
    confirm_install,
    confirm_upgrade,
};

pub fn dependencyAction(found: bool, info: ?AdbInfo) DependencyAction {
    if (!found) return .confirm_install;
    const detected = info orelse return .confirm_upgrade;
    if (!detected.protocol.atLeast(minimum_protocol)) return .confirm_upgrade;
    const release = detected.platform_tools orelse return .confirm_upgrade;
    return if (release.atLeast(minimum_platform_tools)) .use_existing else .confirm_upgrade;
}

pub fn parseInfo(output: []const u8) !AdbInfo {
    return .{
        .protocol = try parseVersionAfter(output, "Android Debug Bridge version "),
        .platform_tools = parseVersionAfter(output, "\nVersion ") catch null,
    };
}

/// Parses Google's SDK repository metadata and returns the newest stable
/// Windows Platform Tools archive. Returned strings borrow from `metadata`.
pub fn latestStableWindowsPackage(metadata: []const u8) !RepositoryPackage {
    var best: ?RepositoryPackage = null;
    var cursor: usize = 0;

    while (findFrom(metadata, cursor, "<remotePackage")) |package_start| {
        const opening_end = findFrom(metadata, package_start, ">") orelse return error.MalformedRepositoryMetadata;
        const opening = metadata[package_start .. opening_end + 1];
        const closing_start = findFrom(metadata, opening_end + 1, "</remotePackage>") orelse {
            return error.MalformedRepositoryMetadata;
        };
        cursor = closing_start + "</remotePackage>".len;

        const package_path = attributeValue(opening, "path") orelse continue;
        if (!std.mem.eql(u8, package_path, "platform-tools")) continue;
        const package_block = metadata[opening_end + 1 .. closing_start];
        if (!isStablePackage(package_block)) continue;

        const revision_block = elementBlock(package_block, "revision") orelse return error.MissingRevision;
        const version = try parseRepositoryVersion(revision_block);
        const archive = try windowsArchive(package_block);
        const candidate = RepositoryPackage{ .version = version, .archive = archive };
        if (best == null or candidate.version.newerThan(best.?.version)) best = candidate;
    }

    return best orelse error.StableWindowsPlatformToolsMissing;
}

fn parseVersionAfter(output: []const u8, marker: []const u8) !Version {
    const marker_index = std.mem.indexOf(u8, output, marker) orelse return error.VersionMarkerMissing;
    const start = marker_index + marker.len;
    var end = start;
    while (end < output.len and (std.ascii.isDigit(output[end]) or output[end] == '.')) : (end += 1) {}
    const text = output[start..end];

    var parts = std.mem.splitScalar(u8, text, '.');
    const major_text = parts.next() orelse return error.InvalidVersion;
    const minor_text = parts.next() orelse return error.InvalidVersion;
    const patch_text = parts.next() orelse return error.InvalidVersion;

    return .{
        .major = try std.fmt.parseInt(u32, major_text, 10),
        .minor = try std.fmt.parseInt(u32, minor_text, 10),
        .patch = try std.fmt.parseInt(u32, patch_text, 10),
    };
}

fn parseRepositoryVersion(revision: []const u8) !Version {
    const major_text = elementText(revision, "major") orelse return error.MissingRevision;
    const minor_text = elementText(revision, "minor") orelse "0";
    const patch_text = elementText(revision, "micro") orelse "0";
    return .{
        .major = try std.fmt.parseInt(u32, std.mem.trim(u8, major_text, " \t\r\n"), 10),
        .minor = try std.fmt.parseInt(u32, std.mem.trim(u8, minor_text, " \t\r\n"), 10),
        .patch = try std.fmt.parseInt(u32, std.mem.trim(u8, patch_text, " \t\r\n"), 10),
    };
}

fn windowsArchive(package_block: []const u8) !RepositoryArchive {
    const archives = elementBlock(package_block, "archives") orelse return error.WindowsArchiveMissing;
    var cursor: usize = 0;
    while (findFrom(archives, cursor, "<archive")) |archive_start| {
        const opening_end = findFrom(archives, archive_start, ">") orelse return error.MalformedRepositoryMetadata;
        const closing_start = findFrom(archives, opening_end + 1, "</archive>") orelse {
            return error.MalformedRepositoryMetadata;
        };
        cursor = closing_start + "</archive>".len;
        const archive_block = archives[opening_end + 1 .. closing_start];
        const host_os = elementText(archive_block, "host-os") orelse continue;
        if (!std.mem.eql(u8, std.mem.trim(u8, host_os, " \t\r\n"), "windows")) continue;

        const complete = elementBlock(archive_block, "complete") orelse return error.MalformedRepositoryMetadata;
        const url = std.mem.trim(u8, elementText(complete, "url") orelse return error.ArchiveUrlMissing, " \t\r\n");
        if (!isSafeRepositoryArchiveName(url)) return error.UnsafeRepositoryUrl;
        const size_text = std.mem.trim(u8, elementText(complete, "size") orelse return error.ArchiveSizeMissing, " \t\r\n");
        const checksum = std.mem.trim(u8, elementText(complete, "checksum") orelse return error.ArchiveChecksumMissing, " \t\r\n");
        const algorithm: RepositoryChecksumAlgorithm = switch (checksum.len) {
            40 => .sha1,
            64 => .sha256,
            else => return error.UnsupportedRepositoryChecksum,
        };
        if (!isHex(checksum)) return error.InvalidRepositoryChecksum;
        return .{
            .relative_url = url,
            .size = try std.fmt.parseInt(u64, size_text, 10),
            .checksum = checksum,
            .checksum_algorithm = algorithm,
        };
    }
    return error.WindowsArchiveMissing;
}

fn isStablePackage(block: []const u8) bool {
    if (std.mem.indexOf(u8, block, "<preview") != null) return false;
    const channel_start = std.mem.indexOf(u8, block, "<channelRef") orelse return true;
    const channel_end_relative = std.mem.indexOfScalar(u8, block[channel_start..], '>') orelse return false;
    const opening = block[channel_start .. channel_start + channel_end_relative + 1];
    const channel = attributeValue(opening, "ref") orelse return false;
    return std.mem.eql(u8, channel, "channel-0");
}

fn isSafeRepositoryArchiveName(name: []const u8) bool {
    return name.len > "platform-tools_-windows.zip".len and
        std.mem.startsWith(u8, name, "platform-tools_") and
        std.mem.endsWith(u8, name, "-windows.zip") and
        std.mem.indexOfAny(u8, name, "/\\") == null and
        std.mem.indexOf(u8, name, "..") == null;
}

fn isHex(text: []const u8) bool {
    if (text.len == 0) return false;
    for (text) |byte| if (!std.ascii.isHex(byte)) return false;
    return true;
}

fn elementBlock(input: []const u8, tag: []const u8) ?[]const u8 {
    var opening_buffer: [64]u8 = undefined;
    var closing_buffer: [68]u8 = undefined;
    const opening = std.fmt.bufPrint(&opening_buffer, "<{s}", .{tag}) catch return null;
    const closing = std.fmt.bufPrint(&closing_buffer, "</{s}>", .{tag}) catch return null;
    const start = std.mem.indexOf(u8, input, opening) orelse return null;
    const opening_end_relative = std.mem.indexOfScalar(u8, input[start..], '>') orelse return null;
    const content_start = start + opening_end_relative + 1;
    const content_end_relative = std.mem.indexOf(u8, input[content_start..], closing) orelse return null;
    return input[content_start .. content_start + content_end_relative];
}

fn elementText(input: []const u8, tag: []const u8) ?[]const u8 {
    return elementBlock(input, tag);
}

fn attributeValue(opening_tag: []const u8, name: []const u8) ?[]const u8 {
    var marker_buffer: [64]u8 = undefined;
    const marker = std.fmt.bufPrint(&marker_buffer, "{s}=\"", .{name}) catch return null;
    const marker_start = std.mem.indexOf(u8, opening_tag, marker) orelse return null;
    const value_start = marker_start + marker.len;
    const value_end_relative = std.mem.indexOfScalar(u8, opening_tag[value_start..], '"') orelse return null;
    return opening_tag[value_start .. value_start + value_end_relative];
}

fn findFrom(input: []const u8, start: usize, needle: []const u8) ?usize {
    if (start > input.len) return null;
    const relative = std.mem.indexOf(u8, input[start..], needle) orelse return null;
    return start + relative;
}

pub fn defaultWindowsInstallPath(allocator: std.mem.Allocator, local_app_data: []const u8) ![]u8 {
    return std.fmt.allocPrint(allocator, "{s}\\Android\\Sdk\\platform-tools", .{std.mem.trimEnd(u8, local_app_data, "\\/")});
}

test "parses protocol and Platform Tools versions" {
    const output =
        \\Android Debug Bridge version 1.0.41
        \\Version 36.0.0-13206524
        \\Installed as C:\\Android\\platform-tools\\adb.exe
    ;
    const info = try parseInfo(output);
    try std.testing.expectEqual(Version{ .major = 1, .minor = 0, .patch = 41 }, info.protocol);
    try std.testing.expectEqual(Version{ .major = 36, .minor = 0, .patch = 0 }, info.platform_tools.?);
}

test "requires upgrade when release version is missing or old" {
    try std.testing.expectEqual(DependencyAction.confirm_install, dependencyAction(false, null));
    try std.testing.expectEqual(DependencyAction.confirm_upgrade, dependencyAction(true, null));
    try std.testing.expectEqual(DependencyAction.confirm_upgrade, dependencyAction(true, .{
        .protocol = minimum_protocol,
        .platform_tools = .{ .major = 29, .minor = 0, .patch = 6 },
    }));
    try std.testing.expectEqual(DependencyAction.use_existing, dependencyAction(true, .{
        .protocol = minimum_protocol,
        .platform_tools = .{ .major = 36, .minor = 0, .patch = 0 },
    }));
}

test "uses an explicit Windows default directory" {
    const path = try defaultWindowsInstallPath(std.testing.allocator, "C:\\Users\\tester\\AppData\\Local\\");
    defer std.testing.allocator.free(path);
    try std.testing.expectEqualStrings("C:\\Users\\tester\\AppData\\Local\\Android\\Sdk\\platform-tools", path);
}

test "selects the newest stable Windows package from official metadata" {
    const metadata =
        \\<sdk-repository>
        \\  <remotePackage path="platform-tools">
        \\    <revision><major>37</major><minor>0</minor><micro>0</micro><preview>1</preview></revision>
        \\    <channelRef ref="channel-1"/>
        \\    <archives><archive><complete><size>999</size><checksum>aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</checksum><url>platform-tools_r37.0.0-rc1-windows.zip</url></complete><host-os>windows</host-os></archive></archives>
        \\  </remotePackage>
        \\  <remotePackage path="platform-tools">
        \\    <revision><major>36</major><minor>0</minor><micro>1</micro></revision>
        \\    <channelRef ref="channel-0"/>
        \\    <archives>
        \\      <archive><complete><size>111</size><checksum>bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb</checksum><url>platform-tools_r36.0.1-darwin.zip</url></complete><host-os>macosx</host-os></archive>
        \\      <archive><complete><size>222</size><checksum type="sha1">cccccccccccccccccccccccccccccccccccccccc</checksum><url>platform-tools_r36.0.1-windows.zip</url></complete><host-os>windows</host-os></archive>
        \\    </archives>
        \\  </remotePackage>
        \\</sdk-repository>
    ;
    const package = try latestStableWindowsPackage(metadata);
    try std.testing.expectEqual(Version{ .major = 36, .minor = 0, .patch = 1 }, package.version);
    try std.testing.expectEqual(@as(u64, 222), package.archive.size);
    try std.testing.expectEqualStrings("platform-tools_r36.0.1-windows.zip", package.archive.relative_url);
    try std.testing.expectEqual(RepositoryChecksumAlgorithm.sha1, package.archive.checksum_algorithm);
}

test "repository parser rejects traversal in an official archive name" {
    const metadata =
        \\<sdk-repository><remotePackage path="platform-tools">
        \\<revision><major>36</major></revision><channelRef ref="channel-0"/>
        \\<archives><archive><complete><size>2</size><checksum>aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</checksum><url>../platform-tools_r36-windows.zip</url></complete><host-os>windows</host-os></archive></archives>
        \\</remotePackage></sdk-repository>
    ;
    try std.testing.expectError(error.UnsafeRepositoryUrl, latestStableWindowsPackage(metadata));
}

test {
    _ = process_supervisor;
    _ = manager;
}
