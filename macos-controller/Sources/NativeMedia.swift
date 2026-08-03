@preconcurrency import AVFoundation
@preconcurrency import CoreImage
@preconcurrency import CoreMedia
@preconcurrency import MetalKit
@preconcurrency import VideoToolbox

enum NativeMediaError: Error {
    case malformedAVCConfiguration
    case mediaFramework(OSStatus)
    case unsupportedTrack
    case audioUnavailable
    case metalUnavailable
}

private enum MediaWire {
    static let video: UInt32 = 1
    static let audio: UInt32 = 2
    static let keyFrame: UInt32 = 1 << 0
    static let codecConfiguration: UInt32 = 1 << 1
    static let discontinuity: UInt32 = 1 << 2
    static let endOfStream: UInt32 = 1 << 3
}

@MainActor
final class MetalVideoView: MTKView {
    private let frameRenderer: MetalFrameRenderer

    init(metalDevice device: MTLDevice) {
        frameRenderer = MetalFrameRenderer(device: device)
        super.init(frame: .zero, device: device)
        framebufferOnly = false
        colorPixelFormat = .bgra8Unorm
        enableSetNeedsDisplay = true
        isPaused = true
        delegate = frameRenderer
    }

    @available(*, unavailable)
    required init(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    static func make() throws -> MetalVideoView {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw NativeMediaError.metalUnavailable
        }
        return MetalVideoView(metalDevice: device)
    }

    func display(_ pixelBuffer: CVPixelBuffer) {
        frameRenderer.replaceFrame(pixelBuffer)
        setNeedsDisplay(bounds)
    }

    func clear() {
        frameRenderer.replaceFrame(nil)
        setNeedsDisplay(bounds)
    }
}

private final class MetalFrameRenderer: NSObject, MTKViewDelegate, @unchecked Sendable {
    private let commandQueue: MTLCommandQueue
    private let context: CIContext
    private let lock = NSLock()
    private var frame: CVPixelBuffer?

    init(device: MTLDevice) {
        guard let queue = device.makeCommandQueue() else {
            fatalError("Metal command queue is unavailable")
        }
        commandQueue = queue
        context = CIContext(mtlDevice: device, options: [.cacheIntermediates: false])
        super.init()
    }

    func replaceFrame(_ value: CVPixelBuffer?) {
        lock.withLock {
            frame = value
        }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        let current = lock.withLock { frame }
        guard let current,
              let drawable = view.currentDrawable,
              let commandBuffer = commandQueue.makeCommandBuffer()
        else { return }

        let source = CIImage(cvPixelBuffer: current)
        let target = CGRect(origin: .zero, size: view.drawableSize)
        let sourceExtent = source.extent
        guard sourceExtent.width > 0, sourceExtent.height > 0, target.width > 0, target.height > 0 else { return }
        let scale = min(target.width / sourceExtent.width, target.height / sourceExtent.height)
        let scaled = source.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let translated = scaled.transformed(
            by: CGAffineTransform(
                translationX: (target.width - scaled.extent.width) / 2 - scaled.extent.minX,
                y: (target.height - scaled.extent.height) / 2 - scaled.extent.minY
            )
        )
        context.render(
            translated,
            to: drawable.texture,
            commandBuffer: commandBuffer,
            bounds: target,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )
        commandBuffer.present(drawable)
        commandBuffer.commit()
    }
}

nonisolated(unsafe) private let decompressionOutput: VTDecompressionOutputCallback = {
    refcon, _, status, _, imageBuffer, presentationTimeStamp, _ in
    guard status == noErr, let refcon, let imageBuffer else { return }
    // refcon 由 passRetained 持有（见 applyConfiguration），回调执行期间 decoder 必然存活
    let decoder = Unmanaged<H264VideoDecoder>.fromOpaque(refcon).takeUnretainedValue()
    decoder.deliver(imageBuffer, presentationTimeStamp: presentationTimeStamp)
}

final class H264VideoDecoder: @unchecked Sendable {
    private let output: @Sendable (CVPixelBuffer) -> Void
    private let lock = NSLock()
    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var configurationID: UInt32 = 0
    // 回调上下文强引用：session 存活期间保持 decoder 不被释放；invalidate 后 release
    private var callbackRef: Unmanaged<H264VideoDecoder>?

    init(output: @escaping @Sendable (CVPixelBuffer) -> Void) {
        self.output = output
    }

    deinit {
        reset()
    }

    func applyConfiguration(_ data: Data, configurationID: UInt32) throws {
        let parameterSets = try Self.parseAVCConfiguration(data)
        var description: CMFormatDescription?
        let status = parameterSets.sps.withUnsafeBytes { spsRaw in
            parameterSets.pps.withUnsafeBytes { ppsRaw in
                guard let spsAddress = spsRaw.bindMemory(to: UInt8.self).baseAddress,
                      let ppsAddress = ppsRaw.bindMemory(to: UInt8.self).baseAddress
                else { return kCMFormatDescriptionError_InvalidParameter }
                var pointers: [UnsafePointer<UInt8>] = [spsAddress, ppsAddress]
                var sizes = [parameterSets.sps.count, parameterSets.pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: pointers.count,
                    parameterSetPointers: &pointers,
                    parameterSetSizes: &sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &description
                )
            }
        }
        guard status == noErr, let videoDescription = description else {
            throw NativeMediaError.mediaFramework(status)
        }

        lock.lock()
        defer { lock.unlock() }
        invalidateLocked()
        // passRetained：每次 session 重建都持有 decoder 一份强引用，回调期间不会 use-after-free
        let retained = Unmanaged.passRetained(self)
        callbackRef = retained
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: decompressionOutput,
            decompressionOutputRefCon: retained.toOpaque()
        )
        var created: VTDecompressionSession?
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        let createStatus = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: videoDescription,
            decoderSpecification: nil,
            imageBufferAttributes: attributes as CFDictionary,
            outputCallback: &callback,
            decompressionSessionOut: &created
        )
        guard createStatus == noErr, let created else {
            throw NativeMediaError.mediaFramework(createStatus)
        }
        format = videoDescription
        session = created
        self.configurationID = configurationID
    }

    func decode(
        _ accessUnit: Data,
        configurationID: UInt32,
        presentationTimeUs: UInt64,
        discontinuity: Bool,
        endOfStream: Bool
    ) throws {
        lock.lock()
        defer { lock.unlock() }
        guard configurationID == self.configurationID, let format, let session else {
            throw NativeMediaError.malformedAVCConfiguration
        }
        if discontinuity {
            VTDecompressionSessionWaitForAsynchronousFrames(session)
        }
        if !accessUnit.isEmpty {
            var block: CMBlockBuffer?
            var status = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: accessUnit.count,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: accessUnit.count,
                flags: 0,
                blockBufferOut: &block
            )
            guard status == kCMBlockBufferNoErr, let block else {
                throw NativeMediaError.mediaFramework(status)
            }
            status = accessUnit.withUnsafeBytes { raw in
                guard let baseAddress = raw.baseAddress else { return kCMBlockBufferBadCustomBlockSourceErr }
                return CMBlockBufferReplaceDataBytes(
                    with: baseAddress,
                    blockBuffer: block,
                    offsetIntoDestination: 0,
                    dataLength: accessUnit.count
                )
            }
            guard status == kCMBlockBufferNoErr else { throw NativeMediaError.mediaFramework(status) }
            var timing = CMSampleTimingInfo(
                duration: .invalid,
                presentationTimeStamp: CMTime(value: CMTimeValue(presentationTimeUs), timescale: 1_000_000),
                decodeTimeStamp: .invalid
            )
            var sampleSize = accessUnit.count
            var sample: CMSampleBuffer?
            status = CMSampleBufferCreateReady(
                allocator: kCFAllocatorDefault,
                dataBuffer: block,
                formatDescription: format,
                sampleCount: 1,
                sampleTimingEntryCount: 1,
                sampleTimingArray: &timing,
                sampleSizeEntryCount: 1,
                sampleSizeArray: &sampleSize,
                sampleBufferOut: &sample
            )
            guard status == noErr, let sample else { throw NativeMediaError.mediaFramework(status) }
            status = VTDecompressionSessionDecodeFrame(
                session,
                sampleBuffer: sample,
                flags: [._EnableAsynchronousDecompression],
                frameRefcon: nil,
                infoFlagsOut: nil
            )
            guard status == noErr else { throw NativeMediaError.mediaFramework(status) }
        }
        if endOfStream {
            VTDecompressionSessionFinishDelayedFrames(session)
            VTDecompressionSessionWaitForAsynchronousFrames(session)
        }
    }

    func reset() {
        lock.lock()
        invalidateLocked()
        lock.unlock()
    }

    fileprivate func deliver(_ pixelBuffer: CVPixelBuffer, presentationTimeStamp: CMTime) {
        _ = presentationTimeStamp
        output(pixelBuffer)
    }

    private func invalidateLocked() {
        if let session {
            VTDecompressionSessionFinishDelayedFrames(session)
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
        format = nil
        configurationID = 0
        // invalidate 之后 VideoToolbox 保证不再回调；此时释放 refcon 强引用
        callbackRef?.release()
        callbackRef = nil
    }

    private static func parseAVCConfiguration(_ data: Data) throws -> (sps: Data, pps: Data) {
        let bytes = [UInt8](data)
        guard bytes.count >= 11, bytes[0] == 1, bytes[4] & 0x03 == 0x03 else {
            throw NativeMediaError.malformedAVCConfiguration
        }
        var cursor = 5
        let spsCount = Int(bytes[cursor] & 0x1f)
        cursor += 1
        guard spsCount >= 1 else { throw NativeMediaError.malformedAVCConfiguration }
        let sps = try readLengthPrefixed(bytes, cursor: &cursor)
        for _ in 1..<spsCount { _ = try readLengthPrefixed(bytes, cursor: &cursor) }
        guard cursor < bytes.count else { throw NativeMediaError.malformedAVCConfiguration }
        let ppsCount = Int(bytes[cursor])
        cursor += 1
        guard ppsCount >= 1 else { throw NativeMediaError.malformedAVCConfiguration }
        let pps = try readLengthPrefixed(bytes, cursor: &cursor)
        for _ in 1..<ppsCount { _ = try readLengthPrefixed(bytes, cursor: &cursor) }
        guard cursor == bytes.count else { throw NativeMediaError.malformedAVCConfiguration }
        return (sps, pps)
    }

    private static func readLengthPrefixed(_ bytes: [UInt8], cursor: inout Int) throws -> Data {
        guard cursor + 2 <= bytes.count else { throw NativeMediaError.malformedAVCConfiguration }
        let length = Int(bytes[cursor]) << 8 | Int(bytes[cursor + 1])
        cursor += 2
        guard length > 0, cursor + length <= bytes.count else {
            throw NativeMediaError.malformedAVCConfiguration
        }
        let value = Data(bytes[cursor..<(cursor + length)])
        cursor += length
        return value
    }
}

final class AACAudioEngine: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let lock = NSLock()
    private var converter: AVAudioConverter?
    private var compressedFormat: AVAudioFormat?
    private var outputFormat: AVAudioFormat?
    private var configurationID: UInt32 = 0
    private var framesPerPacket: UInt32 = 1024

    private struct AudioSpecificConfig {
        let audioObjectType: Int
        let sampleRate: Double
        let channelConfiguration: UInt32
        let framesPerPacket: UInt32
    }

    // ISO 14496-3 AudioSpecificConfig 前 2 字节：audioObjectType(5bit)/samplingFrequencyIndex(4bit)/channelConfiguration(4bit)
    private static func parseAudioSpecificConfiguration(_ data: Data) throws -> AudioSpecificConfig {
        guard data.count >= 2 else { throw NativeMediaError.audioUnavailable }
        let bytes = [UInt8](data)
        let audioObjectType = (Int(bytes[0]) >> 3) & 0x1f
        let samplingFrequencyIndex = ((Int(bytes[0]) & 0x07) << 1) | ((Int(bytes[1]) >> 7) & 0x01)
        let channelConfiguration = (Int(bytes[1]) >> 3) & 0x0f
        // 前 5 位全 1 表示扩展语法（需更多字节），首期不支持
        guard audioObjectType != 31 else { throw NativeMediaError.audioUnavailable }
        let sampleRates: [Double] = [
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
            22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
        ]
        guard samplingFrequencyIndex < sampleRates.count else { throw NativeMediaError.audioUnavailable }
        // HE-AAC（SBR，audioObjectType=5）每包 2048 帧，其余按 1024 推导
        let framesPerPacket: UInt32 = audioObjectType == 5 ? 2048 : 1024
        return AudioSpecificConfig(
            audioObjectType: audioObjectType,
            sampleRate: sampleRates[samplingFrequencyIndex],
            channelConfiguration: UInt32(channelConfiguration),
            framesPerPacket: framesPerPacket
        )
    }

    func applyConfiguration(_ audioSpecificConfiguration: Data, configurationID: UInt32) throws {
        // 用 AudioSpecificConfig 解析出的采样率/声道数配置解码格式，取代硬编码 48k/2ch
        let config = try Self.parseAudioSpecificConfiguration(audioSpecificConfiguration)
        let sampleRate = config.sampleRate
        var channels = config.channelConfiguration
        if channels == 0 {
            // ISO 14496-3 channelConfiguration=0 表示声道数由 PCE 定义；当前解码器只支持 1-2 声道，
            // 回退默认立体声避免整条音频链路不可用，并记录降级原因供电视端排查
            NSLog("AACAudioEngine: channelConfiguration=0（PCE 定义声道），回退默认立体声")
            channels = 2
        }
        guard channels >= 1, channels <= 2 else { throw NativeMediaError.audioUnavailable }
        guard let source = AVAudioFormat(settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
        ]), let destination = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: channels,
            interleaved: false
        ), let converter = AVAudioConverter(from: source, to: destination) else {
            throw NativeMediaError.audioUnavailable
        }

        lock.lock()
        defer { lock.unlock() }
        resetLocked()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: destination)
        do {
            // 启动前检查 isRunning 避免上次 stop 异步完成前的重复 start；失败时清理已 attach 的资源
            if !engine.isRunning { try engine.start() }
            player.play()
        } catch {
            resetLocked()
            throw NativeMediaError.audioUnavailable
        }
        compressedFormat = source
        outputFormat = destination
        self.converter = converter
        self.configurationID = configurationID
        framesPerPacket = config.framesPerPacket
    }

    func play(_ packet: Data, configurationID: UInt32, discontinuity: Bool, endOfStream: Bool) throws {
        lock.lock()
        defer { lock.unlock() }
        guard configurationID == self.configurationID,
              let converter,
              let source = compressedFormat,
              let destination = outputFormat
        else { throw NativeMediaError.audioUnavailable }
        if discontinuity {
            player.stop()
            converter.reset()
            player.play()
        }
        if !packet.isEmpty {
            let compressed = AVAudioCompressedBuffer(
                format: source,
                packetCapacity: 1,
                maximumPacketSize: packet.count
            )
            packet.withUnsafeBytes { raw in
                if let baseAddress = raw.baseAddress {
                    memcpy(compressed.data, baseAddress, packet.count)
                }
            }
            compressed.byteLength = UInt32(packet.count)
            compressed.packetCount = 1
            // packetDescriptions 缺失时显式失败，不静默跳过
            guard let packetDescription = compressed.packetDescriptions else {
                throw NativeMediaError.audioUnavailable
            }
            packetDescription.pointee = AudioStreamPacketDescription(
                mStartOffset: 0,
                mVariableFramesInPacket: framesPerPacket,
                mDataByteSize: UInt32(packet.count)
            )
            guard let pcm = AVAudioPCMBuffer(pcmFormat: destination, frameCapacity: 4096) else {
                throw NativeMediaError.audioUnavailable
            }
            let input = CompressedInputState(buffer: compressed)
            var conversionError: NSError?
            let status = converter.convert(to: pcm, error: &conversionError) { _, inputStatus in
                guard let next = input.take() else {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                inputStatus.pointee = .haveData
                return next
            }
            if let conversionError { throw conversionError }
            guard status == .haveData else { throw NativeMediaError.audioUnavailable }
            player.scheduleBuffer(pcm)
        }
        if endOfStream {
            player.stop()
        }
    }

    func reset() {
        lock.lock()
        resetLocked()
        lock.unlock()
    }

    private func resetLocked() {
        player.stop()
        if player.engine != nil {
            engine.disconnectNodeOutput(player)
            engine.detach(player)
        }
        engine.stop()
        converter = nil
        compressedFormat = nil
        outputFormat = nil
        configurationID = 0
        framesPerPacket = 1024
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}

private final class CompressedInputState: @unchecked Sendable {
    private let lock = NSLock()
    private var buffer: AVAudioCompressedBuffer?

    init(buffer: AVAudioCompressedBuffer) {
        self.buffer = buffer
    }

    func take() -> AVAudioCompressedBuffer? {
        lock.lock()
        defer { lock.unlock() }
        let value = buffer
        buffer = nil
        return value
    }
}

final class NativeMediaPipeline: @unchecked Sendable {
    let video: H264VideoDecoder
    let audio = AACAudioEngine()

    init(videoOutput: @escaping @Sendable (CVPixelBuffer) -> Void) {
        video = H264VideoDecoder(output: videoOutput)
    }

    func consume(
        track: UInt32,
        flags: UInt32,
        configurationID: UInt32,
        presentationTimeUs: UInt64,
        payload: Data
    ) throws {
        let configuration = flags & MediaWire.codecConfiguration != 0
        let discontinuity = flags & MediaWire.discontinuity != 0
        let endOfStream = flags & MediaWire.endOfStream != 0
        switch track {
        case MediaWire.video:
            if configuration {
                try video.applyConfiguration(payload, configurationID: configurationID)
            } else {
                try video.decode(
                    payload,
                    configurationID: configurationID,
                    presentationTimeUs: presentationTimeUs,
                    discontinuity: discontinuity,
                    endOfStream: endOfStream
                )
            }
        case MediaWire.audio:
            if configuration {
                try audio.applyConfiguration(payload, configurationID: configurationID)
            } else {
                try audio.play(
                    payload,
                    configurationID: configurationID,
                    discontinuity: discontinuity,
                    endOfStream: endOfStream
                )
            }
        default:
            throw NativeMediaError.unsupportedTrack
        }
    }

    func reset() {
        video.reset()
        audio.reset()
    }
}
