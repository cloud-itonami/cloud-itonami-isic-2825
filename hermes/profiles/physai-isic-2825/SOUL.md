# physai-isic-2825 — 食品・飲料・たばこ加工機械製造業（ISIC 2825）の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-2825`、ISIC 2825 食品・飲料・たばこ加工機械製造業）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README: ミキサー・充填機・包装機・ボトリングラインを組み立てて試験する工場の運営を調整する actor（robotics authority は full、全 actuation に人の承認が要る HARD gate —— docs/business-model.md）。
その工場のロボットの物理的な仕事（駆動部品の組付け・フレーム材の受入検査・出荷モジュールの搬送）を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:seat-gearmotor-on-frame` | manipulator | 組付けアームがギアモータをキッティング台車からミキサーフレームの駆動プレートへ載せる | 肩関節ピークトルク | 250 N·m（estimate） |
| `:frame-bar-incoming-tensile` | material | フレーム用 304 ステンレス棒（φ8 mm、50.3 mm²）の受入引張試験 | 降伏荷重 | ≥ 10311.5 N（ASTM A240 / A276 304 の最小降伏 205 MPa × 断面積） |
| `:crated-module-to-dock` | transport | AMR タガーが梱包済み充填機モジュールを試験台から出荷ドックへ運ぶ（90 m） | 1 区間の所要時間 | 110 s（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/foodmachmfg/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。repo 自身の test/ の `.cljk` も同じ runner で走る: 79 tests / 215 assertions）。

## 測って分かったこと・限界（成長の第一候補）

1. **組付けアーム**: 肩トルクは積荷 3 kg で 80.7 N·m、20 kg で 200.4 N·m（肘は 14.5 → 55.8 N·m）。関節仕事は 72.3 J → 164.0 J。
   限界 250 N·m を超える積荷は **約 27.0 kg**。ギアモータ単体（〜20 kg）までは入るが、減速機付きの大型駆動ユニットは越える。
2. **受入引張試験**: 降伏荷重は降伏応力 170 MPa で 9000 N、205 MPa で 10800 N、280 MPa で 14600 N。
   判定が切り替わる降伏応力は **約 195.9 MPa** —— 名目 205 MPa より 4〜5 % 低い。solver の降伏検出は 0.2 % offset 則（加工硬化 2 GPa で 0.2 % 塑性ひずみ時に約 +4 MPa）と荷重刻み 100 N で読むため、名目値より高めに出る。
   規格境界ちょうどのロットを合格にしうるので、判定マージンの扱いが成長候補。
3. **搬送**: 所要時間は積荷 100〜500 kg で 77.5 s のまま、800 kg で 77.6 s、1200 kg で 78.5 s。効いているのは速度上限 1.2 m/s と加速度上限 0.4 m/s² で、
   駆動力 600 N が効き始めるのは 800 kg 付近から（`:drive-limited? true`）。限界 110 s を越えるのは積荷 **約 3292 kg**。
   積荷で動くのはエネルギー（6204 J → 21366 J）と転倒余裕（0.931 → 0.893、積荷重心 0.9 m）。停止距離は 1.2 m で一定。
4. **estimate のままの値**（出典に置き換える候補）: 肩トルク上限 250 N·m（20 kg 可搬の産業用アームの仕様書で置き換える）、
   ドック 1 区間 110 s（出荷場の積込みタクトの実績値で置き換える）、アーム・AMR の寸法・質量・駆動力・転がり抵抗係数。
   引張試験のヤング率 193 GPa・加工硬化 2 GPa も材料データシートで裏を取る。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る（例: 充填機の試運転での水の配管流、CIP 洗浄タンクの排水）。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-2825 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-2825 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
