import NitroModules

/// Bench only. The plain-player seek bench measures Media3's ExoPlayer for an Android drag layer
/// (`.devrun/plans/android-preview-speed.md` §6 step 3a); there is nothing to measure on iOS, so
/// every run rejects. It exists so the module builds on both platforms.
final class SeekBench: HybridSeekBenchSpec {
  func run(uri: String, options: SeekBenchOptions) throws -> Promise<SeekBenchResult> {
    return Promise.rejected(withError: RuntimeError("SeekBench is not supported on iOS"))
  }
}
