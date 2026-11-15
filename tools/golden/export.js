#!/usr/bin/env node
/* 기준 구현(docs/03-wireframes/demo-sim.js)에서 시나리오 파일과 골든 JSON을 뽑는다.
   규격은 docs/02-prd/implementation.md "시나리오 파일 규격", "기준 구현과 골든 테스트"다.
   demo-sim.js나 PRD의 규칙·상수·문구를 고치면 다시 돌리고 결과를 함께 커밋한다.

     node app/tools/golden/export.js

   만드는 것
   - app/app/src/demo/assets/s1.json ~ s5.json  재생 시나리오 (demo 빌드 에셋)
   - app/golden/sN.ticks.json                   0.1초 격자 틱 (재생기와 신호 처리 비교용)
   - app/golden/sN.json                         장면, 기본 항목, 위험 장면, 점수, 한 장 문구 */
"use strict";
const fs = require("fs");
const path = require("path");

const APP = path.resolve(__dirname, "..", "..");
require(path.resolve(APP, "..", "docs", "03-wireframes", "demo-sim.js"));
const sim = globalThis.DPSim;

const ASSETS = path.join(APP, "app", "src", "demo", "assets");
const GOLDEN = path.join(APP, "golden");
fs.mkdirSync(ASSETS, { recursive: true });
fs.mkdirSync(GOLDEN, { recursive: true });

// 데모의 clock은 시와 분뿐이다. 날짜는 규격의 예시 날짜로 고정한다
const START_DATE = "2026-11-10";
const pad = (x) => (x < 10 ? "0" : "") + x;

function write(file, text) {
  fs.writeFileSync(file, text.endsWith("\n") ? text : text + "\n", "utf8");
  console.log(path.relative(APP, file));
}

// 사건 한 줄을 한 줄로 쓴다. demo-replay.md "시나리오 형식"의 모양과 같다
function scenarioJson(scn) {
  const head = {
    name: scn.name,
    route: scn.route,
    start: START_DATE + "T" + pad(scn.clock[0]) + ":" + pad(scn.clock[1]),
  };
  const lines = scn.events.map((e) => "    " + JSON.stringify(e));
  return "{\n" + Object.keys(head).map((k) => "  " + JSON.stringify(k) + ": " + JSON.stringify(head[k]) + ",").join("\n") +
    "\n  \"events\": [\n" + lines.join(",\n") + "\n  ]\n}";
}

const r6 = (x) => (x == null ? null : Math.round(x * 1e6) / 1e6);

// 열마다 배열 하나. 값이 없으면 null이다
function ticksJson(res) {
  const cols = {
    t: [], v: [], vs: [], a: [], odo: [], gear: [], sig: [], brake: [], steer: [],
    tbtType: [], tbtD: [], cauType: [], cauD: [], cauLimit: [], tell: [],
  };
  res.ticks.forEach((k) => {
    cols.t.push(Math.round(k.t * 1000));
    cols.v.push(k.v);
    cols.vs.push(r6(k.vs));
    cols.a.push(r6(k.a));
    cols.odo.push(r6(k.odo));
    cols.gear.push(k.gear);
    cols.sig.push(k.sig);
    cols.brake.push(k.brake);
    cols.steer.push(k.steer);
    cols.tbtType.push(k.tbt ? k.tbt.type : null);
    cols.tbtD.push(k.tbt ? r6(k.tbt.d) : null);
    cols.cauType.push(k.cau ? k.cau.type : null);
    cols.cauD.push(k.cau ? r6(k.cau.d) : null);
    cols.cauLimit.push(k.cau && k.cau.limit != null ? k.cau.limit : null);
    const on = Object.keys(k.tell).filter((n) => k.tell[n] !== "OFF").map((n) => n + ":" + k.tell[n]).sort().join(",");
    cols.tell.push(on);
  });
  const body = Object.keys(cols).map((c) => "  " + JSON.stringify(c) + ": " + JSON.stringify(cols[c])).join(",\n");
  return "{\n  \"id\": " + JSON.stringify(res.scn.id) + ",\n  \"route\": " + JSON.stringify(res.scn.route) +
    ",\n  \"endI\": " + res.trip.endI + ",\n" + body + "\n}";
}

function sceneOut(s) {
  const o = { principle: s.principle, t: r6(s.t), s: r6(s.s), est: s.est };
  if (s.kind) o.kind = s.kind;
  if (s.principle === "signal") o.value = r6(s.value);
  if (s.principle === "decel") {
    o.target = s.target; o.aMax = r6(s.aMax); o.thr = s.thr; o.passV = s.passV; o.win = [r6(s.win[0]), r6(s.win[1])];
    if (s.target === "bump") { o.decelS = r6(s.decelS); o.passS = r6(s.passS); }
  }
  if (s.principle === "zone") { o.max = s.max; o.limit = s.limit; }
  return o;
}

// 첫 주행(기록 없음) 기준이다. 누적 기록과 합친 점수는 시드(E-9)에서 따로 본다
function resultJson(res) {
  const ctx = sim.context(res, "none");
  const trip = res.trip;
  const basic = {};
  ["accel", "decel", "over"].forEach((k) => {
    basic[k] = trip.basic[k].map((e) => ({ t: r6(e.t), a: r6(e.a), v: r6(e.v), exempt: e.exempt }));
  });
  const out = {
    id: res.scn.id,
    name: res.scn.name,
    route: trip.route,
    km: r6(trip.km),
    endT: r6(trip.endT),
    tooShort: trip.tooShort,
    hadTbt: trip.hadTbt,
    hadCau: trip.hadCau,
    scenes: trip.scenes.map(sceneOut),
    basic: basic,
    risks: trip.risks.map((r) => ({ type: r.type, t: r6(r.t), aMax: r6(r.aMax), vBefore: r.vBefore })),
    score: {
      P: { signal: r6(ctx.after.P.signal), decel: r6(ctx.after.P.decel), zone: r6(ctx.after.P.zone) },
      S: r6(ctx.after.S), Bobs: r6(ctx.after.Bobs), B: r6(ctx.after.B), T: r6(ctx.after.T),
      withoutExemptT: ctx.cf ? r6(ctx.cf.T) : null,
    },
    card: {
      // 기본 항목 줄에는 demo-sim.js가 원칙을 달지 않는다. cardJson의 BASIC으로 적는다
      lines: ctx.card.lines.map((l) => ({ kind: l.kind, principle: l.k || "basic", partial: !!l.partial, est: l.est, text: l.text })),
      notes: ctx.card.notes,
      scoreLine: ctx.delta,
    },
  };
  return JSON.stringify(out, null, 2);
}

sim.SCENARIOS.forEach((scn) => {
  const res = sim.simulate(scn);
  write(path.join(ASSETS, scn.id + ".json"), scenarioJson(scn));
  write(path.join(GOLDEN, scn.id + ".ticks.json"), ticksJson(res));
  write(path.join(GOLDEN, scn.id + ".json"), resultJson(res));
});

/* ---------- 6개월 시드 (demo-history.js) ----------
   app/app/src/demo/assets/m6.json  시드. 재생하지 않고 바로 저장한다. 날짜는 마지막 주 끝(일요일)에서 며칠 전인지로 쓴다
   app/golden/m6.json               주간 요약, 주 끝 점수, 총점, 주행마다의 한 장 문구 */
const captured = [];
const buildCard = sim.buildCard;
sim.buildCard = function (trip) { const card = buildCard(trip); captured.push({ trip: trip, card: card }); return card; };
// demo-history.js는 화면 코드(demo-view.js)에 기대어 있다. 생성 부분만 쓰므로 빈 자리를 준다
globalThis.DPView = { svg: () => "", IC: {}, screens: {}, titles: {}, progress: () => "" };
require(path.resolve(APP, "..", "docs", "03-wireframes", "demo-history.js"));
sim.buildCard = buildCard;
const H = globalThis.DPHistory.hist;

const SEED_END = new Date(2026, 10, 8); // demo-history.js의 END. 마지막 주의 일요일이다
const daysAgo = (d) => Math.round((SEED_END - d) / 86400000);
const KIND = { "좌회전": "LEFT_TURN", "우회전": "RIGHT_TURN", "유턴": "U_TURN" };
const TOPIC = { signal: "SIGNAL", decel: "DECEL", zone: "ZONE", basic: "BASIC" };

function seedScene(s) {
  if (s.principle === "signal") return { principle: "SIGNAL", kind: KIND[s.kind], s: r6(s.s), onDistanceM: r6(s.value) };
  if (s.principle === "decel") {
    const o = { principle: "DECEL", kind: s.target === "bump" ? "SPEED_BUMP" : KIND[s.kind], s: r6(s.s), maxDecel: r6(s.aMax), hardDecel: s.thr };
    if (s.target === "bump") { o.passKmh = s.passV; o.decelScore = r6(s.decelS); o.passScore = r6(s.passS); }
    return o;
  }
  return { principle: "ZONE", kind: "SCHOOL_ZONE", s: r6(s.s), maxKmh: s.max, limitKmh: s.limit };
}

const exemptsAsc = H.exempts.slice().sort((a, b) => a.date - b.date);
let exemptAt = 0;
const seedTrips = captured.map((c, i) => {
  const t = c.trip, basicEvents = [], exemptEvents = [];
  [["decel", "DECEL"], ["accel", "ACCEL"], ["over", "OVER"]].forEach(([k, kind]) => t.basic[k].forEach((e) => {
    basicEvents.push({ kind: kind, exempt: e.exempt ? e.exempt.toUpperCase() : "NONE" });
    if (e.exempt) {
      const x = exemptsAsc[exemptAt++];
      exemptEvents.push({ decel: x.a, reason: x.type, mode: e.exempt.toUpperCase() });
    }
  }));
  return { daysAgo: daysAgo(H.trips[i].date), km: r6(t.km), scenes: t.scenes.map(seedScene), basicEvents: basicEvents, exemptEvents: exemptEvents };
});
if (exemptAt !== exemptsAsc.length) throw new Error("뺀 급감속을 주행에 다 붙이지 못했다");

const weekOut = (w) => ({
  daysAgo: daysAgo(w.start), trips: w.trips, km: r6(w.km),
  p: { signal: w.p.signal, decel: w.p.decel, zone: w.p.zone },
  avgOnDistanceM: r6(w.m.signal), avgMaxDecel: r6(w.m.decel), maxZoneKmh: w.m.zone,
  missedSignals: w.miss, basic: w.basic, exemptTelltale: w.exempt,
  fix: { SIGNAL: w.fix.signal, DECEL: w.fix.decel, ZONE: w.fix.zone, BASIC: w.fix.basic },
  T: r6(w.T), B: r6(w.B), P: { signal: r6(w.P.signal), decel: r6(w.P.decel), zone: r6(w.P.zone) },
});
H.weeks.forEach((w) => ["signal", "decel", "zone"].forEach((k) => { w.p[k].sum = r6(w.p[k].sum); }));

const seed = {
  name: "6개월 누적 기록",
  kind: "seed",
  trips: seedTrips,
  weekly: H.weeks.map(weekOut),
};
write(path.join(ASSETS, "m6.json"),
  "{\n  \"name\": " + JSON.stringify(seed.name) + ",\n  \"kind\": \"seed\",\n  \"trips\": [\n" +
  seed.trips.map((t) => "    " + JSON.stringify(t)).join(",\n") + "\n  ],\n  \"weekly\": [\n" +
  seed.weekly.map((w) => "    " + JSON.stringify(w)).join(",\n") + "\n  ]\n}");

write(path.join(GOLDEN, "m6.json"), JSON.stringify({
  endDate: "2026-11-08",
  km: r6(H.h.km), trips: H.h.trips,
  score: { P: { signal: r6(H.score.P.signal), decel: r6(H.score.P.decel), zone: r6(H.score.P.zone) }, S: r6(H.score.S), B: r6(H.score.B), T: r6(H.score.T) },
  withoutExemptT: r6(H.cf.T),
  // 주행마다 한 장: 고칠 점의 원칙과 문구. 시드 주행 순서와 같다
  cards: captured.map((c) => {
    const fix = c.card.lines.filter((l) => l.kind === "fix")[0];
    return { fix: fix ? TOPIC[fix.k || "basic"] : null, lines: c.card.lines.map((l) => l.text), notes: c.card.notes };
  }),
}, null, 1));

/* ---------- 경계 시나리오 6종 (implementation.md "기준 구현과 골든 테스트") ----------
   PT에서 재생하는 시나리오가 아니라 판정과 문구의 경계를 보는 골든이다. demo 에셋에 넣지 않는다.
   app/golden/scenarios/bN.json  시나리오
   app/golden/bN.ticks.json, app/golden/bN.json  demo-sim.js로 돌린 결과 */

// 36km/h(초당 10m)로 달리다 회전 30m 앞에서 2초 동안 20km/h까지 줄이고(8km/h/s) 돈다. at은 안내가 시작되는 시각(ms)이다
function brakedTurn(at, type, signalAt, signal) {
  return [
    { t: at, tbt: { type: type, distance: 150 } },
    { t: at + signalAt, turn_signal: signal },
    { t: at + 12000, speed_kmh: 36, tbt: { distance: 30 }, brake: true },
    { t: at + 14000, speed_kmh: 20, tbt: { distance: 14.4 }, brake: false },
    { t: at + 16600, speed_kmh: 20, tbt: { distance: 0 } },
    { t: at + 17600, tbt: { type: "TURN_STRAIGHT", distance: 300 }, turn_signal: "OFF" },
    { t: at + 20600, speed_kmh: 36 },
  ];
}

const BOUNDARY = [
  { id: "b1", name: "회전 3번 중 2번 성공", route: "navi", clock: [9, 0],
    // 깜빡이를 40m, 35m, 12m 전에 켠다. 감속은 세 번 모두 8km/h/s라 성공이 없다. 칭찬이 "3번 중 2번"이 된다
    events: [{ t: 0, gear: "D", speed_kmh: 0 }, { t: 9000, speed_kmh: 36 }]
      .concat(brakedTurn(60000, "TURN_LEFT", 11000, "LEFT"), brakedTurn(100000, "TURN_RIGHT", 11500, "RIGHT"), brakedTurn(140000, "TURN_LEFT", 14400, "LEFT"),
        [{ t: 190000, speed_kmh: 36 }, { t: 199000, speed_kmh: 0 }, { t: 201000, gear: "P" }]) },
  { id: "b2", name: "스쿨존 2곳", route: "navi", clock: [8, 20],
    // 첫 곳은 28km/h로, 둘째 곳은 41km/h로 지난다. 곳마다 판정한다
    events: [
      { t: 0, gear: "D", speed_kmh: 0 }, { t: 9000, speed_kmh: 36 },
      { t: 30000, speed_kmh: 36, caution: { type: "SCHOOL_ZONE", remain: 100, limit: 30 } },
      { t: 36000, speed_kmh: 28 }, { t: 40000, caution: { remain: 0 } }, { t: 82000, caution: null },
      { t: 100000, speed_kmh: 28, caution: { type: "SCHOOL_ZONE", remain: 80, limit: 30 } },
      { t: 110000, speed_kmh: 28, caution: { remain: 0 } }, { t: 114000, speed_kmh: 41 },
      { t: 150000, speed_kmh: 41, caution: null }, { t: 160000, speed_kmh: 0 }, { t: 162000, gear: "P" } ] },
  { id: "b3", name: "같은 방향 깜빡이를 켠 채 이어지는 회전", route: "navi", clock: [13, 10],
    // 좌회전 40m 전에 켜고 끄지 않은 채 120m 뒤에서 다시 좌회전한다. 둘째 회전의 켠 거리는 안내가 시작될 때의 남은 거리다
    events: [
      { t: 0, gear: "D", speed_kmh: 0 }, { t: 9000, speed_kmh: 36 },
      { t: 60000, tbt: { type: "TURN_LEFT", distance: 150 } }, { t: 71000, turn_signal: "LEFT" },
      { t: 75000, tbt: { distance: 0 } }, { t: 75100, tbt: { type: "TURN_SHARP_L", distance: 120 } },
      { t: 87100, tbt: { distance: 0 } }, { t: 88000, tbt: { type: "TURN_STRAIGHT", distance: 400 }, turn_signal: "OFF" },
      { t: 140000, speed_kmh: 36 }, { t: 149000, speed_kmh: 0 }, { t: 151000, gear: "P" } ] },
  { id: "b4", name: "추정 회전 2번이 50m 안", route: "vehicle", clock: [20, 40],
    // 내비 없이 우회전하고 3초(약 17m) 뒤 좌회전한다. 앞 회전만 남는다
    events: [
      { t: 0, gear: "D", speed_kmh: 0 }, { t: 8000, speed_kmh: 30 }, { t: 72000, speed_kmh: 30 }, { t: 76000, speed_kmh: 20 },
      { t: 76000, steering_deg: 320 }, { t: 78500, steering_deg: 0 },
      { t: 81500, speed_kmh: 20, steering_deg: -320 }, { t: 84000, steering_deg: 0 },
      { t: 88000, speed_kmh: 30 }, { t: 130000, speed_kmh: 30 }, { t: 136000, speed_kmh: 0 }, { t: 138000, gear: "P" } ] },
  { id: "b5", name: "기본 항목만 있는 주행", route: "navi", clock: [7, 30],
    // 회전도 주의 지점도 없다. 급감속 2번(20km/h/s)이 한 장의 고칠 점이 된다
    events: [
      { t: 0, gear: "D", speed_kmh: 0 }, { t: 14000, speed_kmh: 54 },
      { t: 40000, speed_kmh: 54, brake: true }, { t: 42000, speed_kmh: 14, brake: false }, { t: 52000, speed_kmh: 54 },
      { t: 80000, speed_kmh: 54, brake: true }, { t: 82000, speed_kmh: 14, brake: false }, { t: 92000, speed_kmh: 54 },
      { t: 130000, speed_kmh: 54 }, { t: 143500, speed_kmh: 0 }, { t: 145000, gear: "P" } ] },
  { id: "b6", name: "주행 시작 때 이미 켜져 있던 경고등", route: "navi", clock: [22, 15],
    // ABS 경고등이 처음부터 켜져 있다. 꺼짐에서 바뀐 것이 아니라 급감속을 면제하지 않는다
    events: [
      { t: 0, gear: "D", speed_kmh: 0, telltale: { ABS: "ON" } }, { t: 14000, speed_kmh: 54 },
      { t: 40000, speed_kmh: 54, brake: true }, { t: 42000, speed_kmh: 14, brake: false }, { t: 52000, speed_kmh: 54 },
      { t: 130000, speed_kmh: 54 }, { t: 143500, speed_kmh: 0 }, { t: 145000, gear: "P" } ] },
];

fs.mkdirSync(path.join(GOLDEN, "scenarios"), { recursive: true });
BOUNDARY.forEach((scn) => {
  const res = sim.simulate(scn);
  write(path.join(GOLDEN, "scenarios", scn.id + ".json"), scenarioJson(scn));
  write(path.join(GOLDEN, scn.id + ".ticks.json"), ticksJson(res));
  write(path.join(GOLDEN, scn.id + ".json"), resultJson(res));
});
