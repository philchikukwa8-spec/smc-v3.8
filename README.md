# SMC Scanner for Android (Android Studio version)

An app for your phone that watches Volatility 75, 50 and 25 and sends a notification when your Smart Money Concepts setup is validated. It does NOT trade for you.

What it does
- Top-down zoom-in. Example: the 4H gives the idea and the target. The 1H gives the entry zone inside it. The app then looks inside that 1H zone on the 5m and then the 1m chart for a more precise entry, with a tighter stop, but the trade keeps the 4H target. The same chain runs for 1D, 1H, 15m and 5m ideas (see Settings and the Backtest tab).
- You get a "zone ready" alert first. If a more precise 5m/1m entry forms within your wait time (Settings > Wait for a precise entry, default 30 minutes), you get the ENTRY alert for it, showing every step of the ladder and how much the risk shrank. If none forms in time, you get the entry on the first zone instead. Many setups will not zoom all the way down, because the 5m and 1m must each pass the full rules.
- The alert gives the full breakdown: entry, stop loss, how much you risk and gain (in points and in your money), first target and next-higher-timeframe target, why it is valid, the validation checklist, risks, and the exact prices where the trade is invalidated.
- Entry style: you can enter at the edge, middle or deep inside the zone. A deeper entry risks less but fills less often. The Backtest tab tests all three and Settings > Entry style > Auto uses the one that earned most.
- Confidence figure: how often similar trades (same grade, same market and pair) won in past backtests, with the number of trades and a likely range, and the win rate you need to break even at that reward:risk. This is measured history, NOT a prediction. With few past trades the app says so instead of showing a percentage.
- Trades tab (trade manager): after an ENTRY alert, tap the idea and press "I entered this trade". The app then notifies you to move the stop to break-even at +1R, where to trail the stop (behind the latest swing), when target 1 is hit (take partial, move the target to the next-timeframe level), liquidity sitting before your target, a stalling trade, and when you are stopped out or reach the target. It tells you; it never touches your broker.
- Keeps scanning in the background (with a small permanent notification) even when the app is closed.
- Has a Backtest tab that shows which quality factors actually helped on past data.

You do not need to write or change any code. Do the steps below exactly.

---------------------------------------------------------------------
PART 1. Install Android Studio (one time, about 20 minutes)
---------------------------------------------------------------------
1. On your computer open https://developer.android.com/studio and download Android Studio (free).
2. Install it and start it. Choose "Standard" setup when asked and let it download what it wants. Wait until it finishes.

---------------------------------------------------------------------
PART 2. Open this project
---------------------------------------------------------------------
1. Unzip SMCScanner-Android.zip somewhere easy, such as your Documents folder.
2. In Android Studio choose Open (or File > Open).
3. Pick the folder called SMCScanner-Android (the one that contains a file named settings.gradle). Click OK.
4. If it asks "Trust project?", click Trust Project.
5. Wait. At the bottom a progress bar says it is syncing and downloading things. The first time this can take 5 to 15 minutes. Do not close it. It is finished when the bar disappears and no red error text shows.
6. If a yellow bar or pop-up offers to install something (for example "Android SDK Platform 34" or "Install missing platform"), click the blue link and accept. If it suggests updating the "Android Gradle Plugin", choose "Remind me later" or just leave it.

---------------------------------------------------------------------
PART 3A. Put the app on your phone with a cable (easiest)
---------------------------------------------------------------------
On your phone:
1. Settings > About phone > tap "Build number" 7 times. It says you are a developer.
2. Settings > System > Developer options > turn on USB debugging.
3. Plug the phone into the computer. When the phone asks "Allow USB debugging?", tap Allow (tick "Always allow").

In Android Studio:
4. At the top, the device list should show your phone's name. If not, unplug and re-plug the cable.
5. Press the green triangle (Run). The app builds and opens on your phone. The first build takes a few minutes.

---------------------------------------------------------------------
PART 3B. Or build an APK file and install it yourself
---------------------------------------------------------------------
1. Menu Build > Build Bundle(s) / APK(s) > Build APK(s).
2. When it finishes, click "locate" in the pop-up in the bottom right. You will see app-debug.apk.
3. Send that file to your phone (USB, Google Drive, WhatsApp to yourself, etc.), tap it, and allow "Install unknown apps" when asked.

---------------------------------------------------------------------
PART 4. First time on the phone (do all of these)
---------------------------------------------------------------------
1. Allow notifications when asked.
2. Open the Settings tab:
   - Press "Send a test notification". If you see it, alerts work.
   - Press "Allow unrestricted battery use" and accept.
   - Press "Start scanning". You will see a small "SMC Scanner is running" notification. That is normal and is how Android allows it to run in the background.
3. On Xiaomi, Oppo, Vivo, Huawei, Samsung and similar phones, also open "Open this app's phone settings" and set Battery to No restrictions, turn Autostart on, and lock the app in the recent-apps list. These phones are aggressive about stopping background apps.
4. Ideas opens with a Today summary (alerts, open trades, R closed today), then your best setup, the ideas being watched and earlier ones. Tap any card for the full breakdown: key numbers first, then sections that fold open. Ideas shows what it is tracking, Trades is the trade manager, Chart is a live Deriv chart (like a mini TradingView) with your zones, entries, stops, targets and risk/reward boxes drawn on it, Alerts is the history, Backtest tests the strategy.
5. In Settings choose your "Risk per trade ($)" and leave Entry style on Auto.
6. Leave scanning on. In the background it measures the past win rates by itself (this uses some data, mostly the first time). You can also press "Calibrate everything now" in Settings, or run the Backtest tab for one market.

---------------------------------------------------------------------
If something goes wrong
---------------------------------------------------------------------
THEME, POSITION SIZE AND DAILY LIMITS (Settings)
---------------------------------------------------------------------
- Appearance: tick or untick "Dark theme" to switch between the dark and the light look (the chart follows).
- Position size: enter your account size and the percent you will risk per trade. Every Risk and Gain figure then uses that amount. The app does not know your broker's lot size, so size each trade on your platform so a stop-out costs about that amount.
- Daily limits: stop new-setup alerts after N losing trades today, and/or set quiet hours. While paused, new-setup alerts are still saved in the Alerts tab, and messages about trades you are already in keep coming. The Ideas screen shows "Alerts paused" and why.
- Trades tab: a "Your results" card shows your own closed trades: win rate, total R and average R.

---------------------------------------------------------------------
SNIPER MODE (fewer, stricter alerts)
---------------------------------------------------------------------
Settings > Sniper mode ON. Then the app only looks at 1D, 4H and 1H ideas, and it only alerts when the entry was confirmed on the 5m / 1m (it never falls back to the rough zone entry). The setup must also have the trend, a major liquidity sweep and strong displacement, and 5 of the 6 quality points.
- Expect only a few alerts. The app does not promise a number per day or an 80% win rate. Nobody can promise that.
- To see what your history says, open Backtest and press "Last 30 days: normal vs Sniper". It downloads the last month of real Deriv prices for all markets and shows trades per day, win rate, total R and how many days had a winner. Judge Sniper mode by that result.
- Winning more often is not the same as making more money. Look at total R as well as win rate.

---------------------------------------------------------------------
THE LIVE CHART
---------------------------------------------------------------------
Every alert notification has two buttons: Open chart (jumps to the live chart for that market) and Snooze 1h (holds back new-setup alerts for an hour; they still appear in the Alerts tab, and messages about a trade you are already in are never snoozed).
Open the Chart tab. Pick a market at the top and a timeframe (1m to 1D).
- Candles stream live from Deriv. The countdown under the price shows the time left on the candle.
- Drag to scroll back in time (older candles load automatically). Pinch to zoom.
- Long-press and drag to see a crosshair with that candle's open/high/low/close.
- While the crosshair is on, a tag shows how far that price is from your trade's entry, in points, R and money. Drag it to a target or stop to see the exact risk and gain.
- Double-tap, or press Latest, to jump back to the newest candle.
- Zones: ON draws your ideas on the chart: the entry zone, a red box for the risk down to the stop, green boxes for TP1/TP2 with R and money, and dashed lines for the precise 5m/1m entries. Your own trades show their live result. Press it to hide them.
- The chart only runs while the Chart tab is open. Alerts keep running in the background without it.

---------------------------------------------------------------------
- "SDK location not found": in Android Studio go to File > Settings > Languages & Frameworks > Android SDK (on Mac: Android Studio > Settings) and let it install the SDK, then File > Sync Project with Gradle Files.
- Red errors after the sync: press File > Sync Project with Gradle Files and check your internet connection. Android Studio must be able to download from Google and Gradle.
- Phone not listed: try another USB cable (some only charge), set the USB mode to File transfer, and accept the debugging prompt on the phone.
- No alerts: check Settings > "Send a test notification", battery settings in Part 4, and that scanning shows ON. Alerts only happen when your strategy actually validates, which may be hours apart.
- Any other error: copy the red text (or take a screenshot) and send it back to me and I will fix it.

Notes
- Market data comes from Deriv's free public feed. No login, no account details, and nothing is sent anywhere else.
- The "score" counts confluences and the "grade" counts quality factors. Neither is a win probability. Volatility indices are simulated, so use the Backtest tab to see what really helped.
- Not financial advice. The app never places trades.

## Excel journal (weekly and monthly records)

On the **Trades** tab, the Signal Record card has **Export Excel journal**. It builds a workbook from every entry alert the app has recorded (plus any trades you logged) and opens the share sheet, so you can save it to Drive/Files or open it in Excel.

What is logged per alert: date, time, weekday, market, side, idea timeframe, entry timeframe, the zoom path, structure (BOS/CHoCH), zone (FVG/OB), Normal or Sniper mode, grade, score, past win rate and its band, entry, stop, target, R:R, risk, outcome (Win / Loss / Open / No fill / Expired), result in R and money.

How the outcome is decided: from price after the alert. The entry must be touched first, then the first of stop or target decides it (stop first if one candle touches both). It does not depend on you taking the trade.

Sheets: **Dashboard** (tiles, "what the data says", 6 charts), **Log**, **Daily**, **Weekly**, **Monthly**, **Setups** (by timeframe, entry, grade, structure, zone, mode), **Search** (filter by market, side, timeframe, grade, outcome, date), **MyTrades**, **Calc**, **Guide**.

Notes: do not edit the data rows on Log or MyTrades (they are replaced on every export); the other sheets are formulas and update by themselves. Small samples (under about 30 decided trades) are not a forecast. The workbook holds the latest 4,999 alerts.

## Build the APK on GitHub (no Android Studio needed)

1. Create a free GitHub account and a new **private** repository (New repository, tick nothing else).
2. On the repository page choose **Add file > Upload files** and drag in everything inside the `SMCScanner` folder (including the hidden `.github` folder; if your file manager hides it, see the note below). Commit.
3. Open the **Actions** tab. The "Build APK" run starts by itself (or choose it on the left and press **Run workflow**). It takes about 5 minutes.
4. When it shows a green tick, open the run and download **SMC-Scanner-APK** from Artifacts at the bottom. Unzip it and install `app-debug.apk` on your phone (allow installs from unknown sources when asked).

Note: if `.github` does not upload, on GitHub choose **Add file > Create new file**, type `.github/workflows/build-apk.yml` as the name and paste in the contents of that file.
If the run shows a red cross, open it and send me the error text; the build has never been run before, so a first fix is likely.

## Master strategy

The app now opens with one combined strategy switched on by default (the old "Sniper" mode): higher-timeframe idea (1D, 4H, 1H) with a major liquidity sweep, a break of structure or change of character with the trend, strong displacement and an FVG/OB zone, then the entry is zoomed to the 5m and 1m, stop beyond the sweep, target on higher-timeframe liquidity. Turn it off in Settings to see every setup again. No win rate is guaranteed: use Backtest > Sniper scorecard and the Excel journal to see the measured result.

## Top-timeframe agreement, pattern filter, trade highlights

- Settings > Master strategy has two switches: "top timeframe trend must agree" (off by default, because it has not been measured on real data yet) and "skip trades when a confirmed chart pattern points the other way" (on by default).
- Tap a trade or idea on the live chart: it draws the swept liquidity pool, the BOS/CHoCH level, the order block and the target pool, and lists which of support/resistance, chart pattern and top-trend agree (+) or disagree (-). Support/resistance zones and chart patterns are drawn on the chart (Zones / Patterns buttons) and major patterns on the 1H/4H/1D send their own notifications.
- Break-even: once a trade you logged is 1R in profit, the app notifies you to move the stop to the entry, then to trail it.

## Advisor (top-down: 1H, 30m, 15m)

The first tab. For every market it always gives a plain answer, never an empty screen:
1. **1H** decides the direction (market structure; if the 1H is ranging it leans from the discount/premium half and says so).
2. **30m and 1H** supply the zone (an unfilled fair value gap or order block in that direction, preferably in discount for buys, premium for sells).
3. **15m** gives the moment: price comes back into the zone, then a 15m candle closes through the last counter-move high (low for sells).

States: **PLAN READY** (entry, stop, target given; you get a notification and it is recorded in the signal record and Excel journal), **IN ZONE** (heads-up; waiting for the 15m close), **ZONE AHEAD**, **WAIT**. Grade A/B/C counts confluences; it is not a win probability. The target is the next 1H liquidity, capped at 2R.

Measured on the 6 simulated test series (walk-forward, no look-ahead): about 1 plan per day per market, 36% won, about +0.08R per trade: roughly break-even. That is a test on simulated data, not proof of an edge. Let the Excel journal collect real results for 2 to 4 weeks before trusting grades or risking more.

## Version 2.0: Legacy Arc Market Intelligence Engine

- **Advisor tab** (first): the Market Intelligence Engine. 4H environment, 1H direction, 30m zone, 15m validation, 5m or 1m entry (the engine compares both and says why it chose one). Every market shows setup state (DETECTED, WATCH, QUALIFIED and so on), signal (STRONG BUY, BUY, STRONG SELL, SELL, WATCH, REJECT), reward:risk with a band label (never used to cancel a plan), evidence for and against, reason codes, data freshness (LIVE, PROVISIONAL, STALE) and a full written explanation ("Why?"). "No trade" is a normal answer.
- **Research tab**: live record from the research database (research.db, separate from all other app files), KPIs by market, band, entry timeframe and counter-trend vs trend-aligned, setup history, replay of the engine on real Deriv history (labelled in-sample, validation, out-of-sample), behaviour discovery with later-data validation and drift warnings, instrument profiles, and strategy versions (propose, approve, roll back; nothing changes by itself).
- **AI**: not included. The app is fully functional without it; the AI assistant from the blueprint is intentionally left out until you decide how to supply a key.
- Qualified setups are also written to the signal record, so they flow into the Excel journal.
- The earlier Advisor (1H > 30m > 15m) stays in the code but is switched off.
- Branding: Legacy Arc emblem as app icon and header, gold-on-navy theme.
- Honest status: the engine is tested on a desktop against simulated series (all tests pass); the phone screens, database and background scanning are type-checked but unproven until you run them. Replay results on simulated data were inconclusive, which is expected: no edge has been demonstrated. Judge it on your own replay and live record.

## Version 2.1: trade cards with a market picture
Each card on the Advisor tab now shows a chart of the last 15m candles with the buy/sell zone (gold), the 15m trigger line, and shaded risk (red) and reward (green) boxes to the stop and target. Entry, stop, target and R:R sit in tiles, and the checklist uses ticks and crosses. Tap the picture to open the live chart. The picture was rendered and checked on a desktop only; check how it looks on your phone.

## Version 2.2: easier Advisor cards
Cards are sorted with ready setups first. Each shows a plain status line, a 5-segment quality bar (higher timeframes agree, evidence groups, no warnings, live data), the chart picture, entry/stop/target/reward tiles, a timeframe strip (up, down, ranging, switching, unclear), ticks for what is confirmed, an amber warnings box, and the technical notes folded under "More details".

## Version 2.3: stricter setups
- Settings: "only call a setup ready when the 1H and 4H both agree" and "keep on watch when opposing structure is under 1R from the entry" (both on by default; switch off for more, looser setups). Setups held back show as WATCH with the reason HTF_NOT_ALIGNED or OPPOSING_STRUCTURE_CLOSE.
- Unfilled 4H order blocks and gaps now count as zones (they are preferred when price is near them).
- Research tab: a "By setup type" table (zone kind and higher-timeframe agreement) with a keep/drop reading. Nothing is judged under 30 completed trades.

## Version 2.4: patterns in the Advisor, clearer chart layers
- The Advisor now reads confirmed 1H chart patterns: one that agrees counts as location evidence, one that points the other way adds a warning (reason code PATTERN_AGAINST).
- Live chart: hint line under the layer bar and a "Key view" button that restores the key layers (setup, BOS/CHoCH, order blocks, FVG, liquidity, trendlines, S/R, patterns). Tap a layer to show or hide it; hold a layer to see only that one.

## Version 3.0: Market's Bird's Eye View (price-action mode)
A second, separate strategy on the same markets (tab "Eye", next to Advisor).
- **Strategy:** chart patterns (double top/bottom, head and shoulders, inverse, triangles, rising/falling wedges), trendline bounces and break-and-retests, and support/resistance rejections and break-and-retests, on closed 1H candles with the 4H trend as context. No order blocks, FVGs or liquidity sweeps. Each signal is a limit order at a level, a stop beyond it, and a target from the pattern's measured move or the nearest real structure. R:R is a label only.
- **Own alerts and explanations:** notification titled "Bird's Eye ...", a plain-language "Why?" for every signal, and a chart picture with the pattern lines and shaded risk and reward. The live chart has a "Bird's Eye setup" layer.
- **Modes:** SMC and price action can run together or on their own (checkboxes on the Eye tab; the background scanner honours them).
- **Memory and learning:** every signal from both modes is recorded with a scenario label (for example "Falling wedge breakout | BUY | with 4H") and its outcome. Each card says how that situation has ended before. The comparison report shows both modes side by side, results per scenario, and what happens when both agree. With "Learning" on, a scenario that lost money over 30+ completed trades (live, validation or out-of-sample rows only, never in-sample) stops alerting.
- **Replay:** "Replay price action on real Deriv history" fills the memory straight away, labelled in-sample / validation / out-of-sample.
- Tab order is now Advisor, Eye, Research, Ideas, Trades, Chart, Alerts, Backtest, Settings.

## Version 3.1: 30m and 15m opportunities in both engines
- **SMC:** two extra cascades run beside the default one (1H direction > 30m zone > 15m trigger > 5m/1m entry). The 30m cascade reads 30m direction > 15m zone > 5m trigger > 1m entry, and the 15m cascade reads 15m > 5m > 1m. Same rules, same evidence groups, same labels; each card shows which cascade it came from, and every alert, row and memory entry carries it (for example "[30m] 15m FVG, aligned"). The 15m cascade needs 1m candles, which Deriv only serves for a few days, so it learns from live signals only; the 30m cascade can also be replayed on the Research tab.
- **Price action:** the same pattern, trendline and level scenarios now also run on the 30m and 15m charts (context from the 1H trend, targets from real structure on 4H/1H/30m). Scenario names end with the chart, such as "Double top breakout (15m)", so each chart builds its own memory and can be switched off by learning on its own record. The replay on the Eye tab covers all three charts.
- **One switch:** Eye tab, "Also look for opportunities on the 30m and 15m" (on by default). Turn it off to go back to the 1H-only behaviour. Expect more alerts, and more noise, on the shorter charts.
- Lower-cascade SMC trades are followed on 1m candles because their stops are tight.

## Version 3.2 - Strategy Lab

The Backtest tab is now the **Lab**.

1. **Choose what to test**: SMC, price action or both; which markets; which charts (1H, 30m, 15m); standard or longer history; and whether to replace earlier results for the same selection. A progress bar shows the run.
2. **Read the results**: filter by mode, market, chart, side and data period (unseen = validation, out-of-sample and live; in-sample = where tuning happened), with or without live trades. You get a plain-English verdict, KPI tiles (trades, win rate, per-trade R, net R, profit factor, drawdown, average win/loss), a 95% range for the win rate, an equity curve, breakdowns (strategy and chart, market, scenario keep-or-drop, best and weakest scenarios, period, side, reward label), **Share results** and **Clear test results**.
3. The older test is still available at the bottom of the Lab as "Classic test".

Notes: fills are simulated (limit entry, stop counted first when one candle touches both, no spread or slippage). The SMC 15m cascade cannot be replayed because Deriv keeps only a few days of 1m candles. Results are history, not a forecast.

## Version 3.3 - order block inside order block, and the price-action guide

**SMC engine**
- New entry refinement (Settings: "SMC: refine entries with order block inside order block", on by default). Top-down: the 1H/30m/4H zone is the parent; inside it the engine looks for a 15m order block (last down candle before a displacement that closed above it, not yet closed through), then a 5m block inside that, then a 1m block inside that. Entry is the near edge of the deepest block (or the current price if already inside it), the stop sits just beyond it. It is used when it is clearly tighter than the older 5m/1m pick, or when no 5m/1m trigger exists. The Advisor evidence shows the chain, e.g. "5m OB inside 15m OB inside 1H zone".
- Realistic higher-timeframe rule: a setup is held back only when the 1H and 4H both clearly point the other way (mixed or unclear is allowed). Counter-trend ideas still need the stricter evidence.
- Works in all three cascades (1H, 30m, 15m directions).

**Price-action engine (pa-2), from the Supreme guide**
- Flags and pennants: pole measured, flag contained and retracing at most half, break of the flag boundary, entry on the retest, target = pole height from the break. Also a heads-up while the flag forms.
- Candle confirmation at levels and trendlines: pin bar, engulfing, fakey (false break of an inside bar). Stops go just beyond the signal candles (never tighter than 0.35 ATR).
- Breakouts: stop beyond the breakout/retest candles instead of a fixed distance; if price pauses sideways after the break, a "no retest" market entry with the stop under the pause.
- Trendlines: lines steeper than about 45 degrees are skipped; more than five touches is flagged.

Notes: on my simulated series the new rules produce about five times more ready setups with about 30% tighter stops (average stop 0.31% -> 0.22% of price), a lower fill rate, and a win rate near 25% with wide targets. Simulated data is not Deriv, so this shows the mechanics work, not that it will make money.

## Version 3.4 - price action is the main engine

- The first tab is now **Price Action** (the old Bird's Eye View). The second tab is **SMC**.
- SMC is **off by default** (new switch at the top of the SMC tab, and in the "Which engines run" card on the Price Action tab). When off it does not scan, alert or record, and its setup is not drawn on the live chart. Anyone who had it switched on before is switched off by this update and can turn it back on.
- The Lab now defaults to testing price action only; tick SMC there to compare the two on the same history.
- Reason: Deriv volatility indices are algorithmic, so the "banks, sessions and stop hunts" story behind order blocks may not apply. Use the Lab to see whether SMC earns anything on real Deriv data before relying on it.

## Version 3.5 - Fib pullback strategy (own engine, tab, chart, alerts and sound)

A third engine beside price action and SMC, built from the trader's written rules.

**The strategy in words**
1. **Trend.** On the H4 (D1 as a second opinion) mark swing highs and lows. Bullish when the last break of structure was up (a candle closed above the previous swing high). Bearish is the mirror.
2. **First fib.** After the break, fib the leg: 0% where it started, 100% at the current high. Wait for price to pull back below the 50% level (30% is a deeper pullback, not guaranteed).
3. **Double confirmation on the M15.** Two consecutive breaks of structure, then a third higher high (lower low for sells).
4. **Second fib.** On the M15 impulse: 100% at the current high, 0% at the high that made the first break. Order at the 50% (or 30%) level, stop on the 0%, target the M15 100% (quick) or the H4 100% (longer).

**What was added to make it sturdier**: leg size filter (1.5 H4 ATR), a pullback that closes through the leg's 0% cancels the idea, M15 breaks must be real steps (3-candle swings, each higher than the last), an M15 impulse under 2 ATR is ignored, a 0.1 ATR stop buffer, a score (D1 agreement, healthy 38-79% pullback, both fibs lining up, strong impulse, fresh H4 break, signal candle), and a setup that ran to its target without filling is dropped.

**In the app**: new **Fib** tab (explanation in words, settings, a chart window with both fibs, the three breaks and the order drawn automatically, M15 and H4 views, a step-by-step checklist per market), its own notification channel with a **sound picker** (plus test button), alerts when the order is ready and again when the entry level is reached, its own research rows and learning, and a Fib option in the Lab (replay on history, results filter "Fib pullback").

Settings: order at 50% or 30%; target M15 or H4; M15 fib 0% at the first-break high (as written) or the base low (wider, safer); hold back setups against D1.

Notes: the tab bar now scrolls (ten tabs). On simulated data the setup is rare (roughly one per market per month) so samples are small; judge it on real Deriv history in the Lab.

## Version 3.6 - side menu, one alert per idea, live setups, "I entered this trade"
- **Side menu.** The tab bar is now a menu you pull out: tap the ☰ button, tap the small grip on the left edge, or swipe right from the left edge. Swipe left, tap outside or press Back to close it. The top bar shows which screen you are on.
- **One price-action alert per idea.** The same idea showing on the 1H, 30m and 15m (or at slightly different entries) is now merged: one notification per market and direction, with the other timeframes listed as "also: ...". A 3-hour quiet period per market and direction, and a 12-hour window that matches entries within 1.5x the risk, stop repeats.
- **Live setups.** Every Price Action, SMC and Fib card has a LIVE line that is rewritten every ~15 seconds from the latest price (how far from the entry, in %, in R; invalidated; target already reached; at the entry; how close to the stop). The lists also reload by themselves when a scan finishes.
- **"I entered this trade"** on every setup, ready or not. Entry defaults to the current price; stop and target are filled from the setup and can be edited. The trade then appears in Trades and is tracked like any other (break-even, trail, target). It records whether the setup met every condition when you entered. The Trades tab also has "Log a trade I took" for trades with no setup behind them.
- Honest limits: the app tracks and advises; it does not place orders. The live line uses the 1-minute close from Deriv, so it can lag a few seconds. The Android screens were type-checked only, not run on a phone; the engines were tested on simulated data.

## Version 3.7 - every volatility index, one card per setup, signal-focused charts, Fib backtest

- **Separate setups, as before.** The merging of the 1H, 30m and 15m versions of an idea is removed. Every chart keeps its own setups, each with its own card, entry, stop, target, full explanation and alert. To stop repeats, the quiet period is now kept per market, side and chart (a 15m and a 1H setup never silence each other).
- **A section per market.** Price Action has an Overview and a button for every volatility index with its setup count (and how many are ready). Each market page shows how many setups were detected, how many are ready or being watched, the split by chart (1H/30m/15m) and by side, data status and age.
- **Bigger, fuller cards.** Charts are taller and tap to enlarge; each card shows VALIDATION POINTS (what is true, what is still needed), the invalidation level, the target, warnings and the full explanation (no longer behind a button).
- **Live chart opens the signal.** The button opens the chart on that signal's own timeframe (a 15m signal opens the 15m chart) and draws only that signal: entry, invalidation (stop), target, the pattern or fib lines. "Show everything on this chart" brings the normal layers back.
- **All volatility indices** (Volatility 10, 25, 50, 75, 100 and the 1-second 10, 15, 25, 30, 50, 75, 90, 100, 150, 250) are scanned by Price Action, Fib and SMC and appear in the Chart tab, the Lab and Settings (each can be switched off).
- **Fib tab**: a market button with counts, one big chart per setup (M15 or H4 view, tap to enlarge) with both fibs and the strategy-progress checklist, a Live chart button, and the Fib backtest is back on the tab (pick markets, run, read win rate with 95% range, R, profit factor, drawdown).
- **Chart tab**: 30m added (1m, 5m, 15m, 30m, 1H, 4H, 1D).
- **Guide strategies added or tightened (pa-3)**: rounding bottom/top, cup and handle (and inverted), trend-channel overshoot fade, sloping-flag rejection, head-and-shoulders rules (extended move, rising/flat neckline, shoulders in one band), double-top extended move, "safety net" (a close back through the neckline cancels the pattern), three real touches on each wedge/triangle side, long-base note, Fibonacci 61.8%/50% and 200-average confluence, and the 2:1 minimum warning on trendline trades.
- Fixed a display bug in the Fib "how it works" text (it showed \n characters).
- Notes: scanning 15 markets takes longer and uses more data per scan; switch markets off in Settings to speed it up. The Android screens were type-checked only, not run on a phone.

## Version 3.8 - double confirmation, top-down only, no conflicts, ENTRY EXPIRED

- **Fib filters removed.** The Fib engine now applies only the rules you gave: H4 break of structure, pullback, three consecutive M15 breaks of structure, entry on the 50% / 61.8%, stop on the M15 0%, target the H4 100%. Nothing else filters it out.
- **Double confirmation on price action.** Confirmation 1 is the structure signal. Confirmation 2 is a break of structure in the trade direction on the lower chart (15m for 1H ideas, 5m for 30m and 15m ideas) after price reached the entry zone. It adds a point to the confidence score and shows as a "2nd confirmation" marker on the chart.
- **Top-down only.** Setups against the higher-timeframe trend are dropped unless they are marked as a reversal.
- **One direction per market.** If a buy and a sell conflict, only the most confident one is kept, trend-aligned first. This applies within price action only, not across PA / Fib / SMC.
- **Higher timeframes first** in the card list.
- **Signal card plan:** setup identified, waiting for confirmation, place a BUY/SELL LIMIT at, invalid if.
- **ENTRY EXPIRED** stamp on price action and Fib cards. Expired setups that were not logged move to the bottom for 24 h with a reason and a "Remove this setup" button.
- **Chart notes** (the text such as "falling wedge") are hidden by default; the "Notes" chip turns them on.
- Lab replays of price action have no lower-chart data, so double confirmation shows n/a there.
