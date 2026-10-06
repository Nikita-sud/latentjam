"""Replay SMART the way the app asks for it: one plan after another, each seeded with the queue's last track.

    python replay_plans.py --benchmark ~/Documents/LJ/model-diet-2026-10-04 --source ~/Documents/LJ/latentjam-diet \
        --prepared <features dir> --assets <bundle> --seeds <seed file> --mode history --plans 12,12,12 \
        --variant off --variant continue --variant carry --out <new dir>

The app's queue top-up (EngineNextTrackChooser) plans 12 tracks per request; the For You hero, the map's
regions and "start SMART" plan the chosen queue length first. Each later request excludes everything
queued and hands the chain the observed history plus the track it continues from (smartHistoryFor).

Variants: `off` is the shipped chain; `continue` is ChainTuning.continueAfterExhaustion with every plan
starting afresh (the listening build before 2026-10-06's fix); `carry` also resumes the walk the previous
plan ended with (ChainWalk); `join` also orders each resumed plan from the track it continues
(JourneySequencer `from`), which is what DefaultSimilarityEngine does in the continuation mode;
`cfg:mode=..,b=..,s=..,w=..` sets ChainTuning.neighbourhoodBonus, semanticWeight and
neighbourhoodDescriptorWeight on one of those variants.

Output per variant, one line per seed: key, played track ids (journey order, all plans), plan boundaries,
each plan's walk intent, a 0/1 flag per played track for "close to the original pick" by the chain's own
criterion (Reanchor.isCloseContinuation), per plan `suitable/close/length` (suitable tracks still available
when the plan was requested: close to the original pick, not queued, not kept out by the session, not a
queued title, artist under the cap; then how many of the plan's tracks were close) and the size of the
pick's neighbourhood. A plan with fewer close tracks than min(suitable, length) left the neighbourhood
while suitable tracks remained.
"""
import argparse
import concurrent.futures as cf
import hashlib
import json
from pathlib import Path
import subprocess
import sys

LOOP_START = "                val encodes0 = runtime.encodes\n"
LOOP_END = "                if (index % 10 == 0 || index == jobs.lastIndex) {\n"

PLAN_LOOP = """                val failures0 = runtime.failures
                val plans = System.getProperty("diet.plans").split(',').map { it.trim().toInt() }
                val continuation = System.getProperty("diet.continue") == "true"
                val carry = System.getProperty("diet.carry") == "true"
                val join = System.getProperty("diet.join") == "true"
                val bonus = System.getProperty("diet.bonus")?.toFloatOrNull() ?: Float.POSITIVE_INFINITY
                val semantic = System.getProperty("diet.sem")?.toFloatOrNull() ?: 1f
                val descriptorShare = System.getProperty("diet.nbw")?.toFloatOrNull() ?: 0.5f
                val ringStep = System.getProperty("diet.ringStep")?.toFloatOrNull() ?: 0f
                val ringFloor = System.getProperty("diet.ringFloor")?.toFloatOrNull() ?: 0.20f
                val ringPull = System.getProperty("diet.ringPull")?.toFloatOrNull() ?: 1f
                val styleGate = System.getProperty("diet.styleGate")?.toFloatOrNull() ?: Float.NEGATIVE_INFINITY
                val tracing = System.getProperty("diet.trace") == "true"
                val traced = StringBuilder()
                val seedRow = snapshot.rowOf(job.seed)
                fun close(row: Int) = Reanchor.isCloseContinuation(
                    snapshot.centeredCosine(seedRow, row), snapshot.descriptorCosine(seedRow, row),
                )
                // What the chain keeps out as played in this session (SmartChain.prepareContext and
                // sessionExclusions, less the starvation guard, which needs a nearly empty library).
                fun sessionRows(events: List<SmartHistoryEvent>, seed: Int): Set<Int> {
                    val known = events.mapNotNull { e ->
                        snapshot.rowOf(e.trackId).takeIf { it >= 0 }?.let { it to e.startedAtMs }
                    }.sortedBy { it.second }
                    if (known.isEmpty()) return emptySet()
                    val aligned = if (known.last().first == seed) known else known + (seed to known.last().second + 1)
                    var first = aligned.lastIndex
                    while (first > 0) {
                        val gap = aligned[first].second - aligned[first - 1].second
                        if (gap < 0 || gap > 30L * 60L * 1000L) break
                        first--
                    }
                    return aligned.subList(first, aligned.size).map { it.first }.toSet() - seed
                }
                // Suitable = close to the original pick by the chain's own criterion, not queued, not
                // kept out by the session, not a queued title, artist under the chain's cap.
                fun suitableLeft(spent: Set<Int>, kept: Set<Int>): Int {
                    val titles = spent.mapNotNull { snapshot.tracks[it].meta.titleArtistKey }.toSet()
                    val artists = spent.groupingBy { snapshot.tracks[it].meta.artistKey }.eachCount()
                    return snapshot.tracks.indices.count { row ->
                        val meta = snapshot.tracks[row].meta
                        row !in spent && row !in kept && close(row) && meta.titleArtistKey !in titles &&
                            (meta.artistKey.isEmpty() || (artists[meta.artistKey] ?: 0) < ChainConfig.CHAIN_ARTIST_QUEUE_CAP)
                    }
                }
                val queued = HashSet<Int>().apply { add(seedRow) }
                val played = ArrayList<Int>()
                val bounds = ArrayList<Int>()
                val intents = ArrayList<String>()
                val planStats = ArrayList<String>()
                var tail = job.seed
                var walk: ChainWalk? = null
                // smartHistoryFor: the observed events, then the track the request continues from.
                val observed = if (job.history.isEmpty()) emptyList() else job.history.dropLast(1)
                val now = job.history.lastOrNull()?.startedAtMs ?: 1000L
                for ((plan, length) in plans.withIndex()) {
                    val events = observed + SmartHistoryEvent(tail, now, playedFraction = 1f, completed = true, skipped = false)
                    val left = suitableLeft(queued, sessionRows(events, snapshot.rowOf(tail)))
                    val eligible = BooleanArray(snapshot.size) { it !in queued }
                    val resumed = if (carry) walk else null
                    val result = SmartChain(
                        snapshot, runtime, eligible,
                        tuning = ChainTuning(
                            continueAfterExhaustion = continuation, neighbourhoodBonus = bonus,
                            semanticWeight = semantic, neighbourhoodDescriptorWeight = descriptorShare,
                            ringStep = ringStep, ringFloor = ringFloor, ringSeedPull = ringPull, styleGate = styleGate,
                        ),
                    ).build(
                        seedId = tail, length = length, timeFeatures = job.timeFeatures, historyEvents = events,
                        resume = resumed,
                        trace = if (!tracing) null else { t: PickTrace ->
                            if (traced.isNotEmpty()) traced.append(',')
                            traced.append("{\\"plan\\":").append(plan).append(",\\"position\\":").append(t.position)
                                .append(",\\"track\\":\\"").append(snapshot.tracks[t.row].id.value)
                                .append("\\",\\"channel\\":\\"").append(t.channel)
                                .append("\\",\\"intent\\":\\"").append(snapshot.tracks[t.intentRow].id.value)
                                .append("\\",\\"intent_moved\\":").append(t.intentMoved).append(",\\"ring\\":").append(t.ring)
                                .append(",\\"in_neighbourhood\\":").append(t.inNeighbourhood)
                                .append(",\\"neighbourhood\\":").append(t.neighbourhoodSize).append(",\\"pool\\":").append(t.poolSize)
                                .append(",\\"logit\\":").append(t.logit).append(",\\"terms\\":[")
                                .append(t.terms.joinToString(",")).append("]}")
                        },
                    )
                    // DefaultSimilarityEngine orders a resumed plan from the track it continues.
                    val ordered = if (join && resumed != null) {
                        JourneySequencer.order(snapshot, result.rows, from = snapshot.rowOf(tail))
                    } else {
                        harnessJourneyOrder(snapshot, result.rows) ?: fail("this source tree has no JourneySequencer")
                    }
                    if (ordered.isEmpty()) break
                    planStats += "$left/${ordered.count { close(it) }}/${ordered.size}"
                    played += ordered
                    queued += ordered
                    bounds += played.size
                    intents += result.walk?.intent?.value ?: "-"
                    walk = result.walk
                    tail = snapshot.tracks[ordered.last()].id
                }
                val failures = runtime.failures - failures0
                if (failures > 0) {
                    val problem = "seed ${job.key}: $failures predictor failures"
                    if (!options.allowFallback) fail(problem)
                    System.err.println("[runner] warning: $problem")
                    fallbackSeeds++
                }
                val neighbourhood = snapshot.tracks.indices.count { it != seedRow && close(it) }
                if (tracing) {
                    java.io.File(options.output.path + ".trace.jsonl").appendText("{\\"key\\":\\"" + job.key + "\\",\\"picks\\":[" + traced + "]}\\n")
                }
                out.append(job.key).append('\\t')
                    .append(played.joinToString(",") { snapshot.tracks[it].id.value }).append('\\t')
                    .append(bounds.joinToString(",")).append('\\t')
                    .append(intents.joinToString(",")).append('\\t')
                    .append(played.joinToString("") { if (close(it)) "1" else "0" }).append('\\t')
                    .append(planStats.joinToString(";")).append('\\t')
                    .append(neighbourhood.toString()).append('\\n')
"""

VARIANTS = {"off": ("false", "false", "false"), "continue": ("true", "false", "false"),
            "carry": ("true", "true", "false"), "join": ("true", "true", "true")}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for name in ("benchmark", "source", "prepared", "assets", "seeds", "out"):
        ap.add_argument(f"--{name}", type=Path, required=True)
    ap.add_argument("--mode", choices=("cold", "history"), required=True)
    ap.add_argument("--plans", default="12,12,12", help="plan lengths in request order")
    ap.add_argument("--variant", action="append", required=True,
                    help=f"one of {sorted(VARIANTS)}, or cfg:mode=<variant>,b=<neighbourhood bonus>,"
                         "s=<semantic weight>,w=<neighbourhood descriptor share>,r=<ring step>,f=<ring floor>,"
                         "p=<seed pull inside a widened ring>,g=<style gate>")
    ap.add_argument("--pool", type=int, default=3)
    ap.add_argument("--trace", action="store_true", help="also write <variant>.tsv.trace.jsonl with every pick's PickTrace")
    a = ap.parse_args()
    if not all(p.strip().isdigit() and int(p) > 0 for p in a.plans.split(",")):
        ap.error("--plans must be positive integers")
    a.out.mkdir(parents=True, exist_ok=True)
    runner = (a.benchmark / "smart/src/Runner.kt").read_text()
    start, end = runner.find(LOOP_START), runner.find(LOOP_END)
    if start < 0 or end < start or runner.count(LOOP_START) != 1:
        raise ValueError("unsupported harness source: the per-seed loop was not found once")
    runner_path = a.out / "Runner.kt"
    runner_path.write_text(runner[:start] + PLAN_LOOP + runner[end:])
    build = a.out / "build"
    if not (build / "launch.json").exists():
        subprocess.run([sys.executable, str(a.benchmark / "smart/build.py"), "--src", str(a.source),
                        "--runner", str(runner_path), "--out", str(build)], check=True)
    launch = json.loads((build / "launch.json").read_text())

    def run(variant):
        bonus, semantic, share, step, floor, pull, gate = "inf", "1", "0.5", "0", "0.2", "1", "-inf"
        if variant.startswith("cfg:"):
            spec = dict(item.split("=", 1) for item in variant[4:].split(","))
            mode = spec.get("mode", "join")
            bonus, semantic, share = spec.get("b", bonus), spec.get("s", semantic), spec.get("w", share)
            step, floor, pull = spec.get("r", step), spec.get("f", floor), spec.get("p", pull)
            gate = spec.get("g", gate)
            [float(x) for x in (bonus, semantic, share, step, floor, pull, gate)]  # validates
            cont, carry, join = VARIANTS[mode]
            variant = f"{mode}_b{bonus}_s{semantic}_w{share}" + (f"_r{step}_f{floor}" if float(step) else "") + \
                (f"_p{pull}" if float(pull) != 1 else "") + (f"_g{gate}" if gate != "-inf" else "")
        else:
            cont, carry, join = VARIANTS[variant]
        output = a.out / f"{variant}.tsv"
        command = launch[:1] + [f"-Ddiet.plans={a.plans}", f"-Ddiet.continue={cont}", f"-Ddiet.carry={carry}",
                                f"-Ddiet.join={join}", f"-Ddiet.bonus={bonus}", f"-Ddiet.sem={semantic}",
                                f"-Ddiet.nbw={share}", f"-Ddiet.ringStep={step}", f"-Ddiet.ringFloor={floor}",
                                f"-Ddiet.ringPull={pull}", f"-Ddiet.styleGate={gate}", f"-Ddiet.trace={str(a.trace).lower()}"] + launch[1:] + [
            str(a.prepared.resolve()), str(a.assets.resolve()), a.mode, str(output.resolve()),
            "--seeds", str(a.seeds.resolve()), "--order", "journey",
        ]
        with (a.out / f"{variant}.log").open("w") as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        return variant, command

    with cf.ThreadPoolExecutor(a.pool) as pool:
        commands = dict(pool.map(run, a.variant))
    manifest = dict(plans=a.plans, mode=a.mode, commands=commands,
                    source_build_sha256=sha(build / "source-hashes.json"), seeds_sha256=sha(a.seeds),
                    models={n: sha(a.assets / n) for n in ("predictor_state.onnx", "predictor_scorer_n100.onnx")},
                    prepared_inputs={n: sha(a.prepared / n) for n in
                                     ("audio.f32", "text.f32", "descriptor.f32", "energy.f32", "meta.tsv")
                                     if (a.prepared / n).exists()})
    (a.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(a.out)


if __name__ == "__main__":
    main()
