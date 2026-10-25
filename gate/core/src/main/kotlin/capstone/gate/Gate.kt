package capstone.gate

/**
 * 독립 정지 게이트(D18·D35·D36). 모든 액션은 실행 직전 [check], 실행 직후 [checkAfter]를 지난다.
 *
 * - 판정은 Allow | Block 둘뿐이고, 판정 중 예외가 나면 Block(FAIL_CLOSED_INTERNAL_EXCEPTION).
 * - 확정·결제 신호나 예상 밖 앱 전환이 실행 뒤에 보이면 잠긴다(lock). 잠금은 사용자가 직접
 *   조작을 넘겨받은 뒤 [unlockByUser]로만 풀린다. LLM·DSL 경로에는 잠금 해제 수단이 없다(단조성).
 */
class Gate(
    private val policy: GatePolicy,
    private val sink: DecisionSink = DecisionSink.NONE,
) {
    private data class Counts(val nodes: Int, val interactive: Int)

    private val baselines = HashMap<String, ArrayDeque<Counts>>()
    private var lockReason: BlockReason? = null
    private var seq = 0L

    /**
     * 실행 직후 캡처에 앞 앱 창이 없어(전환 애니메이션 중) 패키지 전환을 확인하지 못했을 때, 실행한 패키지.
     * 다음에 gate가 처음 보는 앞 앱과 비교한다(I-88).
     */
    private var unverifiedFrom: String? = null

    val lockedReason: BlockReason? @Synchronized get() = lockReason

    @Synchronized
    fun check(before: Snapshot, request: ActionRequest): Decision =
        guarded("check", before, request) { evaluateBefore(before, request) }

    @Synchronized
    fun checkAfter(before: Snapshot, after: Snapshot, request: ActionRequest): Decision =
        guarded("after", after, request) { evaluateAfter(before, after, request) }

    /** 사용자가 직접 조작을 넘겨받은 뒤(우리 UI의 명시적 버튼)에만 호출한다. */
    @Synchronized
    fun unlockByUser() {
        lockReason = null
        unverifiedFrom = null
        baselines.clear()
    }

    private inline fun guarded(phase: String, snap: Snapshot, request: ActionRequest, body: () -> Decision): Decision {
        val decision = try {
            body()
        } catch (t: Throwable) {
            Decision.Block(BlockReason.FAIL_CLOSED_INTERNAL_EXCEPTION, listOf("exception:${t::class.simpleName}"))
        }
        return try {
            sink.record(entryOf(phase, snap, request, decision))
            decision
        } catch (t: Throwable) {
            // 증명 로그를 못 남기면 M7을 증명할 수 없으므로 실행하지 않는다.
            Decision.Block(BlockReason.FAIL_CLOSED_INTERNAL_EXCEPTION, listOf("log_failed:${t::class.simpleName}"))
        }
    }

    // ---- 실행 전 ----

    private fun evaluateBefore(before: Snapshot, req: ActionRequest): Decision {
        lockReason?.let { return Decision.Block(it, listOf("locked")) }
        pendingSwitch(before)?.let { return it }
        common(before, req)?.let { return it }

        val fg = before.foregroundAppWindow!!
        collapseAgainstBaseline(before, fg)?.let { return it }

        val signals = mutableListOf<String>()
        if (req is ActionRequest.Targeted) {
            targetChecks(fg, before, req, signals)?.let { return it }
        }
        remember(before, fg)
        return Decision.Allow(signals)
    }

    /** 실행 전·후 공통 fail-closed. */
    private fun common(snap: Snapshot, req: ActionRequest): Decision.Block? {
        snap.screenshotError?.let { code ->
            // 3(INTERVAL_TIME_SHORT)은 호출자가 재시도한 뒤에도 남아 있으면 차단(D36).
            val tag = if (code == 3) "retryable" else "error"
            return Decision.Block(BlockReason.FAIL_CLOSED_SCREENSHOT_ERROR, listOf("screenshot_error:$code", tag))
        }
        if (snap.windows.isEmpty()) return Decision.Block(BlockReason.FAIL_CLOSED_EMPTY_TREE, listOf("no_windows"))
        if (snap.hasDuplicateWindowIds) {
            return Decision.Block(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, listOf("duplicate_window_id"))
        }
        val fg = snap.foregroundAppWindow
            ?: return Decision.Block(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, listOf("no_foreground_app_window"))
        if (fg.packageName == null || fg.packageName !in policy.allowedPackages) {
            return Decision.Block(BlockReason.PACKAGE_NOT_ALLOWED, listOf("pkg:${fg.packageName}"))
        }
        if (req.expectedPackage != null && req.expectedPackage != fg.packageName) {
            return Decision.Block(
                BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH,
                listOf("expected:${req.expectedPackage}", "actual:${fg.packageName}"),
            )
        }
        if (isEffectivelyEmpty(fg)) {
            return Decision.Block(BlockReason.FAIL_CLOSED_EMPTY_TREE, listOf("nodes:${fg.nodeCount}"))
        }
        if (ConfirmMatcher.isPaymentScreen(fg)) {
            return Decision.Block(BlockReason.CONFIRM_NODE_TEXT, listOf("payment_screen"))
        }
        if (snap.screen == Snapshot.SCREEN_UNKNOWN) {
            return Decision.Block(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, listOf("screen:UNKNOWN"))
        }
        if (req.expectedScreen != null && req.expectedScreen != snap.screen) {
            return Decision.Block(
                BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE,
                listOf("expected:${req.expectedScreen}", "actual:${snap.screen}"),
            )
        }
        return null
    }

    /**
     * 의미 있는 노드(라벨 또는 클릭·입력 가능)가 하나도 없으면 빈 트리로 본다.
     * WebView 결제처럼 컨테이너만 남은 트리(T6)도 여기에 걸린다.
     */
    private fun isEffectivelyEmpty(w: UiWindow): Boolean =
        w.root == null || w.nodes.values.none { it.hasLabel || it.clickable || it.editable }

    private fun targetChecks(
        fg: UiWindow,
        before: Snapshot,
        req: ActionRequest.Targeted,
        signals: MutableList<String>,
    ): Decision.Block? {
        val t = req.target
        val window = before.window(t.windowId)
            ?: return Decision.Block(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, listOf("stale_window:${t.windowId}"))
        if (window.id != fg.id) {
            return Decision.Block(BlockReason.PACKAGE_NOT_ALLOWED, listOf("target_outside_foreground_app:${window.packageName}"))
        }
        if (window.treeHash != t.treeHash) {
            return Decision.Block(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, listOf("tree_hash_mismatch"))
        }
        val node = window.nodes[t.key]
            ?: return Decision.Block(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, listOf("node_missing:${t.key}"))

        ConfirmMatcher.hardReason(node)?.let { return Decision.Block(it, listOf("target:${t.key}")) }

        if (req is ActionRequest.Click || req is ActionRequest.TapNodeCenter || req is ActionRequest.ImeEnter) {
            delegateCheck(window, t.key)?.let { return it }
            contextCheck(window, t.key)?.let { return it }
        }
        if (req is ActionRequest.TapNodeCenter) {
            gestureOverlap(before, node)?.let { return it }
        }
        if (req is ActionRequest.ImeEnter) imeEnterCheck(before, window)?.let { return it }
        if (ConfirmMatcher.hasPartial(node)) signals += "partial:${t.key}"
        return null
    }

    /**
     * 클릭이 실제로 닿는 노드(대상이 클릭 불가면 가장 가까운 클릭 가능 조상)와 그 서브트리 라벨을 합쳐 본다.
     * 텍스트가 자식에 있고 클릭은 부모가 받는 흔한 구조(G5)를 잡는다.
     */
    private fun delegateCheck(window: UiWindow, key: String): Decision.Block? {
        val effective = window.ancestorsOf(key).firstOrNull { window.nodes[it]?.clickable == true } ?: key
        val scope = listOf(effective) + window.ancestorsOf(key).takeWhile { it != effective } + window.descendantsOf(effective)
        for (k in scope.distinct()) {
            if (k == key) continue
            val n = window.nodes[k] ?: continue
            if (ConfirmMatcher.hardReason(n) != null) {
                return Decision.Block(BlockReason.CONFIRM_ANCESTOR_DELEGATE, listOf("effective:$effective", "matched:$k"))
            }
        }
        return null
    }

    /** '확인'·'계속' 같은 일반 버튼은 가까운 조상 서브트리 문맥이 결제·확정이면 차단한다(T2). */
    private fun contextCheck(window: UiWindow, key: String): Decision.Block? {
        val node = window.nodes[key] ?: return null
        val label = ConfirmMatcher.labelsOf(node).firstOrNull { ConfirmMatcher.isGenericAffirmative(it) }
            ?: window.descendantsOf(key).mapNotNull { window.nodes[it] }
                .flatMap { ConfirmMatcher.labelsOf(it) }.firstOrNull { ConfirmMatcher.isGenericAffirmative(it) }
            ?: return null
        val scopeRoot = window.ancestorsOf(key).drop(1).take(policy.contextAncestorDepth).lastOrNull() ?: return null
        val contextKey = (listOf(scopeRoot) + window.descendantsOf(scopeRoot)).firstOrNull { k ->
            window.nodes[k]?.let { n -> ConfirmMatcher.labelsOf(n).any { ConfirmMatcher.isConfirmContext(it) } } == true
        } ?: return null
        return Decision.Block(
            BlockReason.CONFIRM_ANCESTOR_DELEGATE,
            listOf("generic:${LabelNormalizer.normalize(label)}", "context:$contextKey"),
        )
    }

    /**
     * 좌표 탭은 노드가 아니라 그 자리를 덮는 무엇이든 받는다. 그래서 대상 창만이 아니라 화면의 모든 창을,
     * 확정 노드만이 아니라 확정 라벨을 품은 클릭 가능 노드(G5 위임)까지 본다(TapHitTestFuzzTest·I-87).
     * 창 순서(z-order)는 보지 않는다 — 아래 창과 겹쳐도 막는 쪽(fail-closed).
     */
    private fun gestureOverlap(snap: Snapshot, node: UiNode): Decision.Block? {
        val tap = Bounds.point(node.bounds.centerX, node.bounds.centerY).inflate(policy.touchSlopPx)
        for (w in snap.windows) {
            for ((k, n) in w.nodes) {
                if (!n.bounds.intersects(tap)) continue
                val matched = if (ConfirmMatcher.hardReason(n) != null) {
                    k
                } else if (n.clickable) {
                    w.descendantsOf(k).firstOrNull { d -> w.nodes[d]?.let(ConfirmMatcher::hardReason) != null }
                } else {
                    null
                } ?: continue
                return Decision.Block(BlockReason.CONFIRM_GESTURE_OVERLAP, listOf("overlap:${w.id}:$k", "matched:$matched"))
            }
        }
        return null
    }

    /**
     * IME 엔터는 노드가 아니라 앱의 입력 처리기가 받아 무엇이든 제출할 수 있다. 그래서 대상 앱의 모든 창에서
     * 확정 신호가 하나라도 보이면 막는다. 클릭 가능 여부는 보지 않는다: 글자는 자식·클릭은 부모(G5, S3 구조)이거나
     * WebView 제출 버튼처럼 클릭 표시가 없을 수 있다(ImeEnterFuzzTest·I-93). 키보드·시스템 창은 다른 패키지라 뺀다.
     */
    private fun imeEnterCheck(snap: Snapshot, target: UiWindow): Decision.Block? {
        for (w in snap.windows) {
            if (w.packageName != target.packageName) continue
            val k = w.nodes.entries.firstOrNull { (_, n) -> ConfirmMatcher.hardReason(n) != null }?.key ?: continue
            return Decision.Block(BlockReason.CONFIRM_ANCESTOR_DELEGATE, listOf("ime_enter_with_confirm_on_screen:${w.id}:$k"))
        }
        return null
    }

    // ---- 실행 후 ----

    private fun evaluateAfter(before: Snapshot, after: Snapshot, req: ActionRequest): Decision {
        lockReason?.let { return Decision.Block(it, listOf("locked")) }
        val beforeFg = before.foregroundAppWindow
        pendingSwitch(after)?.let { return it }
        val afterFg = after.foregroundAppWindow
        if (beforeFg != null && afterFg != null && beforeFg.packageName != afterFg.packageName) {
            return lock(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, listOf("switched:${beforeFg.packageName}->${afterFg.packageName}"))
        }
        // 앞 앱이 안 보이면 전환 여부를 아직 모른다. 판단을 미루고, 다음에 보이는 앞 앱으로 정한다.
        if (afterFg == null && beforeFg != null) unverifiedFrom = beforeFg.packageName
        // 실행 후에는 '다음 화면'이 예상 화면과 다를 수 있으므로 expectedScreen은 보지 않는다.
        val block = common(after, stripExpectedScreen(req))
        if (block != null) {
            return if (block.reason.isConfirm || block.reason == BlockReason.PACKAGE_NOT_ALLOWED) {
                lock(block.reason, block.signals)
            } else {
                block
            }
        }
        val fg = after.foregroundAppWindow!!
        // 새 화면이 분류되지 않았으면 직전 화면과 직접 비교해 급감을 본다(D35 ②).
        // 분류됐으면 그 (패키지, 화면) 기준선과 비교한다: 안 보면 같은 화면으로 분류된 숨은 화면이 기준선에
        // 들어가 중앙값을 끌어내리고, 다음 check에서 숨은 화면이 통과한다(CollapseBaselineFuzzTest·I-97).
        if (after.screen == null) {
            if (beforeFg != null) collapsed(Counts(beforeFg.nodeCount, beforeFg.interactiveCount), fg)?.let { return it }
        } else {
            collapseAgainstBaseline(after, fg)?.let { return it }
        }
        remember(after, fg)
        return Decision.Allow()
    }

    /**
     * 실행 뒤 처음 보이는 앞 앱이 실행한 패키지와 다르면, 그 사이 전환 중 캡처가 끼었어도 직접 전환과 같이 잠근다.
     * 전환 중 캡처(빈 트리·앞 앱 없음)는 잠그지 않으므로(I-12) 이것이 없으면 '실행 → 전환 중 → 다른 앱 → 복귀'가
     * 잠금 없이 이어졌다(AppSwitchLockFuzzTest·I-88).
     */
    private fun pendingSwitch(snap: Snapshot): Decision.Block? {
        val from = unverifiedFrom ?: return null
        val pkg = snap.foregroundAppWindow?.packageName ?: return null
        unverifiedFrom = null
        if (pkg == from) return null
        return lock(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, listOf("switched:$from->$pkg", "after_unverified_capture"))
    }

    private fun stripExpectedScreen(req: ActionRequest): ActionRequest = when (req) {
        is ActionRequest.Click -> req.copy(expectedScreen = null)
        is ActionRequest.SetText -> req.copy(expectedScreen = null)
        is ActionRequest.Scroll -> req.copy(expectedScreen = null)
        is ActionRequest.TapNodeCenter -> req.copy(expectedScreen = null)
        is ActionRequest.ImeEnter -> req.copy(expectedScreen = null)
        is ActionRequest.GlobalBack -> req.copy(expectedScreen = null)
    }

    private fun lock(reason: BlockReason, signals: List<String>): Decision.Block {
        lockReason = reason
        return Decision.Block(reason, signals + "lock")
    }

    // ---- 급감 기준선 ----

    /** 기준선은 (패키지, 화면) 단위. 화면이 바뀌면 새 기준선이라 정상 전환을 급감으로 오인하지 않는다. */
    private fun baselineKey(snap: Snapshot, fg: UiWindow) = "${fg.packageName}|${snap.screen}"

    private fun collapseAgainstBaseline(snap: Snapshot, fg: UiWindow): Decision.Block? {
        val hist = baselines[baselineKey(snap, fg)] ?: return null
        if (hist.isEmpty()) return null
        val median = Counts(hist.map { it.nodes }.median(), hist.map { it.interactive }.median())
        return collapsed(median, fg)
    }

    private fun collapsed(base: Counts, now: UiWindow): Decision.Block? {
        if (base.nodes < policy.collapseMinBaseline) return null
        val nodeDrop = now.nodeCount < base.nodes * policy.collapseRatio
        val interDrop = now.interactiveCount < base.interactive * policy.collapseRatio
        if (!(nodeDrop && interDrop)) return null
        return Decision.Block(
            BlockReason.FAIL_CLOSED_TREE_COLLAPSE,
            listOf("nodes:${base.nodes}->${now.nodeCount}", "interactive:${base.interactive}->${now.interactiveCount}"),
        )
    }

    /** 급감으로 막힌 스냅샷은 기준선에 넣지 않는다(숨은 화면이 기준선을 끌어내리지 않도록). */
    private fun remember(snap: Snapshot, fg: UiWindow) {
        val q = baselines.getOrPut(baselineKey(snap, fg)) { ArrayDeque() }
        q.addLast(Counts(fg.nodeCount, fg.interactiveCount))
        while (q.size > policy.baselineWindow) q.removeFirst()
    }

    private fun List<Int>.median(): Int = sorted().let { it[it.size / 2] }

    private fun entryOf(phase: String, snap: Snapshot, req: ActionRequest, d: Decision): DecisionEntry {
        val fg = runCatching { snap.foregroundAppWindow }.getOrNull()
        return DecisionEntry(
            ts = snap.timestampMs,
            seq = ++seq,
            phase = phase,
            pkg = fg?.packageName,
            windows = snap.windows.map { "${it.type}:${it.packageName}" },
            screen = snap.screen,
            decision = if (d is Decision.Allow) "ALLOW" else "BLOCK",
            reason = (d as? Decision.Block)?.reason,
            signals = d.signals,
            action = req.kind,
            target = (req as? ActionRequest.Targeted)?.target?.key,
            treeHash = fg?.let { runCatching { it.treeHash }.getOrNull() },
            nodeCount = fg?.nodeCount ?: 0,
            interactiveCount = fg?.interactiveCount ?: 0,
        )
    }
}
