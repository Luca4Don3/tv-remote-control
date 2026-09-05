/*
 * 128-bit float glue for Apple platforms.
 *
 * Zig std 的 f128 路径（std.fmt.parseFloat / std.json innerParse）会引用
 * musl 风格的 128-bit 数学 C 接口（roundq 等）；Apple libSystem 不提供，
 * compiler-rt 仅提供 __*tf2 内建符号。此文件将缺位的 C 接口转发到
 * compiler-rt，供 iOS/macOS 链接使用（与 libclang_rt 一并链接）。
 */
typedef __float128 q128;

extern q128 __roundtf2(q128);

q128 roundq(q128 x) { return __roundtf2(x); }
