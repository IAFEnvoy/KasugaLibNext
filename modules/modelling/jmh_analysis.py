#!/usr/bin/env python3
import json
from pathlib import Path
import matplotlib.pyplot as plt
import matplotlib as mpl
import numpy as np

# 中文显示支持
mpl.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'STHeiti', 'DejaVu Sans']
mpl.rcParams['axes.unicode_minus'] = False

ROOT = Path(__file__).parent
RESULTS_JSON = ROOT / 'build' / 'reports' / 'jmh' / 'results.json'
OUT_DIR = ROOT / 'build' / 'reports' / 'jmh'
OUT_DIR.mkdir(parents=True, exist_ok=True)

TPS = 20
BUDGET_MS = 1000.0 / TPS  # 每 tick 50 ms 预算


def parse_results(path: Path) -> list[dict]:
    with open(path, 'r') as f:
        return json.load(f)


def short_name(benchmark: str) -> str:
    parts = benchmark.split('.')
    return f'{parts[-2]}.{parts[-1]}'


def mspt_per_tick(ns_per_op: float, instances: np.ndarray) -> np.ndarray:
    """每 tick 都调用时的 MSPT（毫秒）。"""
    return ns_per_op * instances / 1_000_000.0


def mspt_event(ns_per_op: float, instances: np.ndarray, events_per_second: float) -> np.ndarray:
    """每秒 events_per_second 次事件，分摊到 20 tick 的 MSPT（毫秒）。"""
    return ns_per_op * instances * events_per_second / (TPS * 1_000_000.0)


def max_instances_per_tick(ns_per_op: float) -> int:
    if ns_per_op <= 0:
        return 0
    return int(BUDGET_MS * 1_000_000.0 / ns_per_op)


def max_instances_event(ns_per_op: float, events_per_second: float) -> int:
    if ns_per_op <= 0 or events_per_second <= 0:
        return 0
    return int(BUDGET_MS * TPS * 1_000_000.0 / (ns_per_op * events_per_second))


def main():
    raw = parse_results(RESULTS_JSON)

    # 提取 JMH 元信息（取第一条）
    meta = raw[0] if raw else {}
    jmh_version = meta.get('jmhVersion', '未知')
    jvm = meta.get('jvm', '未知')
    jdk = meta.get('jdkVersion', '未知')
    warmup = f"{meta.get('warmupIterations', '?')} x {meta.get('warmupTime', '?')}"
    measurement = f"{meta.get('measurementIterations', '?')} x {meta.get('measurementTime', '?')}"

    metrics = {}
    for item in raw:
        name = short_name(item['benchmark'])
        pm = item['primaryMetric']
        metrics[name] = {
            'score': pm['score'],
            'error': pm['scoreError'],
            'unit': pm['scoreUnit'],
            'benchmark': item['benchmark'],
        }

    mux_tick = metrics['MultiplexerBenchmark.perTick']
    mux_event = metrics['MultiplexerBenchmark.onEvent']
    fsm_tick = metrics['StateMachineBenchmark.perTick']
    fsm_event = metrics['StateMachineBenchmark.onEvent']

    # 汇总文本（中文）
    summary_lines = [
        '======== JMH 基准测试结果 ========',
        f'JMH 版本: {jmh_version}',
        f'JVM: {jvm}',
        f'JDK: {jdk}',
        f'Warmup: {warmup}',
        f'Measurement: {measurement}',
        '',
        '基准测试耗时（ns/op）：',
        f'  Multiplexer.perTick   = {mux_tick["score"]:.3f} ± {mux_tick["error"]:.3f}',
        f'  Multiplexer.onEvent   = {mux_event["score"]:.3f} ± {mux_event["error"]:.3f}',
        f'  StateMachine.perTick  = {fsm_tick["score"]:.3f} ± {fsm_tick["error"]:.3f}',
        f'  StateMachine.onEvent  = {fsm_event["score"]:.3f} ± {fsm_event["error"]:.3f}',
        '',
        f'游戏 TPS = {TPS}，每 tick 预算 = {BUDGET_MS:.1f} ms',
        '',
        '50 ms 预算下最大安全实例数：',
        f'  Multiplexer.perTick       = {max_instances_per_tick(mux_tick["score"]):,}',
        f'  StateMachine.perTick      = {max_instances_per_tick(fsm_tick["score"]):,}',
        f'  Multiplexer.onEvent@1/s   = {max_instances_event(mux_event["score"], 1.0):,}',
        f'  StateMachine.onEvent@1/s  = {max_instances_event(fsm_event["score"], 1.0):,}',
        f'  Multiplexer.onEvent@10/s  = {max_instances_event(mux_event["score"], 10.0):,}',
        f'  StateMachine.onEvent@10/s = {max_instances_event(fsm_event["score"], 10.0):,}',
        '',
        '不同实例数下的 MSPT 增长：',
        f'{"实例数":>12} | Multiplexer.perTick | Multiplexer.onEvent@1/s | StateMachine.perTick | StateMachine.onEvent@1/s',
    ]
    for n in [100, 1000, 10000, 100000, 500000, 1000000]:
        mt = mspt_per_tick(mux_tick['score'], np.array([n]))[0]
        me1 = mspt_event(mux_event['score'], np.array([n]), 1.0)[0]
        ft = mspt_per_tick(fsm_tick['score'], np.array([n]))[0]
        fe1 = mspt_event(fsm_event['score'], np.array([n]), 1.0)[0]
        summary_lines.append(
            f'{n:>12,} | {mt:18.3f} ms | {me1:22.3f} ms | {ft:19.3f} ms | {fe1:23.3f} ms'
        )
    summary = '\n'.join(summary_lines)
    print(summary)
    (OUT_DIR / 'tps_mspt.txt').write_text(summary)

    # 详细图表
    fig = plt.figure(figsize=(16, 11))
    gs = fig.add_gridspec(2, 1, height_ratios=[3, 1], hspace=0.12)
    ax = fig.add_subplot(gs[0])
    ax_table = fig.add_subplot(gs[1])

    N = np.geomspace(100, 10_000_000, 150).astype(np.int64)

    series = [
        ('Multiplexer.perTick', mux_tick, 0, 'per_tick', '#1f77b4', 'Multiplexer 每 tick'),
        ('StateMachine.perTick', fsm_tick, 0, 'per_tick', '#ff7f0e', 'StateMachine 每 tick'),
        ('Multiplexer.onEvent@10/s', mux_event, 10.0, 'event', '#2ca02c', 'Multiplexer 事件 10/s'),
        ('StateMachine.onEvent@10/s', fsm_event, 10.0, 'event', '#d62728', 'StateMachine 事件 10/s'),
    ]

    for _, info, events, _, color, label in series:
        score = info['score']
        error = info['error']
        if events == 0:
            y = mspt_per_tick(score, N)
            y_low = mspt_per_tick(max(score - error, 1e-9), N)
            y_high = mspt_per_tick(score + error, N)
        else:
            y = mspt_event(score, N, events)
            y_low = mspt_event(max(score - error, 1e-9), N, events)
            y_high = mspt_event(score + error, N, events)

        ax.fill_between(N, y_low, y_high, color=color, alpha=0.15)
        ax.plot(N, y, color=color, linewidth=2.5, marker='', label=label)

        # 标出跨越 50 ms 的位置
        if events == 0:
            n_cross = max_instances_per_tick(score)
        else:
            n_cross = max_instances_event(score, events)
        if 100 <= n_cross <= 10_000_000:
            y_cross = BUDGET_MS
            ax.annotate(
                f'{n_cross:,}',
                xy=(n_cross, y_cross),
                xytext=(n_cross * 0.3, y_cross * 2.5),
                fontsize=9,
                color=color,
                arrowprops=dict(arrowstyle='->', color=color, lw=0.8),
            )

    ax.axhline(BUDGET_MS, color='red', linestyle='--', linewidth=1.5, label='50 ms tick 预算线')
    ax.axhspan(BUDGET_MS, BUDGET_MS * 10, color='red', alpha=0.05)

    ax.set_xscale('log')
    ax.set_yscale('log')
    ax.set_xlim(100, 10_000_000)
    ax.set_ylim(0.0005, 200)
    ax.set_xlabel('活跃实例数（个）', fontsize=12)
    ax.set_ylabel('每 tick 增加 MSPT（毫秒）', fontsize=12)
    ax.set_title('Multiplexer / StateMachine 对游戏 TPS/MSPT 的影响评估（基于 JMH）', fontsize=14, pad=12)
    ax.grid(True, which='both', linestyle='--', alpha=0.4)
    ax.legend(loc='upper left', fontsize=10)

    # 环境信息文本框
    env_text = (
        f'JMH {jmh_version}  |  {jvm}  |  JDK {jdk}\n'
        f'Warmup {warmup}  |  Measurement {measurement}  |  每条线 ±scoreError 阴影带\n'
        '假设：每 tick/事件调用一次；未计入其它游戏逻辑开销'
    )
    ax.text(
        0.98, 0.02, env_text,
        transform=ax.transAxes,
        fontsize=9,
        verticalalignment='bottom',
        horizontalalignment='right',
        bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.85, edgecolor='gray')
    )

    # 底部 JMH 结果表格
    table_data = [
        ['Multiplexer.perTick',  f'{mux_tick["score"]:.3f}',  f'{mux_tick["error"]:.3f}',  f'{max_instances_per_tick(mux_tick["score"]):,}', f'{max_instances_event(mux_tick["score"], 10.0):,}'],
        ['Multiplexer.onEvent',  f'{mux_event["score"]:.3f}',  f'{mux_event["error"]:.3f}',  f'{max_instances_event(mux_event["score"], 1.0):,}', f'{max_instances_event(mux_event["score"], 10.0):,}'],
        ['StateMachine.perTick', f'{fsm_tick["score"]:.3f}',  f'{fsm_tick["error"]:.3f}',  f'{max_instances_per_tick(fsm_tick["score"]):,}', f'{max_instances_event(fsm_tick["score"], 10.0):,}'],
        ['StateMachine.onEvent', f'{fsm_event["score"]:.3f}',  f'{fsm_event["error"]:.3f}',  f'{max_instances_event(fsm_event["score"], 1.0):,}', f'{max_instances_event(fsm_event["score"], 10.0):,}'],
    ]
    col_labels = ['基准测试', '耗时 (ns/op)', '误差 (ns/op)', 'perTick 最大实例数', 'event@10/s 最大实例数']

    ax_table.axis('off')
    table = ax_table.table(
        cellText=table_data,
        colLabels=col_labels,
        loc='center',
        cellLoc='center',
        colColours=['#eeeeee'] * len(col_labels),
    )
    table.auto_set_font_size(False)
    table.set_fontsize(10)
    table.scale(1.2, 1.8)

    fig.savefig(OUT_DIR / 'tps_mspt_detail.png', dpi=150, bbox_inches='tight')
    print(f'\n详细图表已保存至 {OUT_DIR / "tps_mspt_detail.png"}')
    print(f'汇总文本已保存至 {OUT_DIR / "tps_mspt.txt"}')


if __name__ == '__main__':
    main()
