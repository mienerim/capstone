package capstone.tools.replay

import capstone.gate.ActionRequest
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.NodeRef
import capstone.gate.RecordingDecisionSink
import capstone.gate.Snapshot
import capstone.gate.exec.GatedExecutor
import java.io.File

object Fixtures {
    val dir: File = File(System.getProperty("capstone.fixtures") ?: "fixtures")
    val mockJsonl: File get() = File(dir, "mock/mock-korail.jsonl")
    val happy: File get() = File(dir, "mock/happy.json")
    val faults: File get() = File(dir, "mock/faults.json")
    val srt: File get() = File(dir, "mock/srt.json")

    fun pool(): SnapshotPool = SnapshotPool.load(listOf(mockJsonl))
}

/** 재생 장치 + 실제 Gate + GatedExecutor 조립(앱의 조립과 같은 모양). */
class Rig(scenarioFile: File = Fixtures.happy, scenario: Scenario = Scenario.read(scenarioFile)) {
    val sink = RecordingDecisionSink()
    val gate = Gate(GatePolicy(allowedPackages = setOf(GatePolicy.MOCK_APP), ownPackage = "capstone.app"), sink)
    val device = ReplayDevice(scenario, SnapshotPool.forScenario(scenarioFile, scenario), oracleScreens = true)
    val executor = GatedExecutor(gate, device, device)

    fun snap(): Snapshot = device.capture()

    fun ref(s: Snapshot, label: String): NodeRef {
        val w = s.foregroundAppWindow!!
        val key = w.nodes.entries.first { (_, n) -> n.text == label || n.contentDescription == label || n.hint == label }.key
        // 텍스트 노드가 클릭 불가면 가장 가까운 클릭 가능 조상을 고른다(에이전트가 할 일과 같음).
        val clickKey = w.ancestorsOf(key).firstOrNull { w.nodes.getValue(it).clickable || w.nodes.getValue(it).editable } ?: key
        return NodeRef.of(w, clickKey)
    }

    fun click(label: String) = snap().let { s -> executor.execute(s, ActionRequest.Click(ref(s, label), expectedScreen = s.screen)) }
    fun type(label: String, text: String) = snap().let { s -> executor.execute(s, ActionRequest.SetText(ref(s, label), text, expectedScreen = s.screen)) }
}
