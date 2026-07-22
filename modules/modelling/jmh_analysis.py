#!/usr/bin/env python3
import json
import os
from pathlib import Path
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).parent
RESULTS_JSON = ROOT / 'build' / 'reports' / 'jmh' / 'results.json'
OUT_DIR = ROOT / 'build' / 'reports' / 'jmh'
OUT_DIR.mkdir(parents=True, exist_ok=True)

TPS = 20
BUDGET_MS = 1000.0 / TPS  # 50 ms per tick

INSTANCES = [100, 1000, 5000, 10000, 50000, 100000]


def parse_results(path: Path) -> dict[str, float]:
    with open(path, 'r') as f:
        data = json.load(f)
    scores = {}
    for item in data:
        parts = item['benchmark'].split('.')
        name = f'{parts[-2]}.{parts[-1]}'  # e.g. "StateMachineBenchmark.onEvent"
        scores[name] = item['primaryMetric']['score']
    return scores


def mspt_per_tick(ns_per_op: float, instances: int) -> float:
    """Per-tick MSPT when `advance`/`tick` is called every tick for `instances`."""
    return ns_per_op * instances / 1_000_000.0


def mspt_event(ns_per_op: float, instances: int, events_per_second: float) -> float:
    """Per-tick MSPT when `events_per_second` events occur per instance, spread over TPS ticks."""
    return ns_per_op * instances * events_per_second / (TPS * 1_000_000.0)


def max_instances_before_budget(ns_per_op: float, budget_ms: float = BUDGET_MS) -> int:
    if ns_per_op <= 0:
        return 0
    return int(budget_ms * 1_000_000.0 / ns_per_op)


def main():
    scores = parse_results(RESULTS_JSON)

    # map short benchmark labels to scores
    mux_per_tick = scores.get('MultiplexerBenchmark.perTick', 0.0)
    mux_event = scores.get('MultiplexerBenchmark.onEvent', 0.0)
    fsm_per_tick = scores.get('StateMachineBenchmark.perTick', 0.0)
    fsm_event = scores.get('StateMachineBenchmark.onEvent', 0.0)

    summary_lines = [
        'JMH baseline (ns/op):',
        f'  Multiplexer.perTick   = {mux_per_tick:.3f}',
        f'  Multiplexer.onEvent   = {mux_event:.3f}',
        f'  StateMachine.perTick  = {fsm_per_tick:.3f}',
        f'  StateMachine.onEvent  = {fsm_event:.3f}',
        '',
        f'TPS = {TPS}, MSPT budget = {BUDGET_MS:.1f} ms',
        '',
        'Max instances before consuming full 50 ms tick budget:',
        f'  Multiplexer.perTick   = {max_instances_before_budget(mux_per_tick):,}',
        f'  StateMachine.perTick  = {max_instances_before_budget(fsm_per_tick):,}',
        f'  Multiplexer.onEvent@1/s  = {int(BUDGET_MS / mspt_event(mux_event, 1, 1.0)):,}',
        f'  StateMachine.onEvent@1/s = {int(BUDGET_MS / mspt_event(fsm_event, 1, 1.0)):,}',
        '',
        'MSPT impact by instance count:',
        f'{"instances":>12} | Mux.perTick | Mux.event1/s | FSM.perTick | FSM.event1/s',
    ]

    mux_tick_vals = [mspt_per_tick(mux_per_tick, n) for n in INSTANCES]
    mux_event1_vals = [mspt_event(mux_event, n, 1.0) for n in INSTANCES]
    fsm_tick_vals = [mspt_per_tick(fsm_per_tick, n) for n in INSTANCES]
    fsm_event1_vals = [mspt_event(fsm_event, n, 1.0) for n in INSTANCES]

    for n, mt, me, ft, fe in zip(INSTANCES, mux_tick_vals, mux_event1_vals, fsm_tick_vals, fsm_event1_vals):
        summary_lines.append(f'{n:>12,} | {mt:10.3f} ms | {me:11.3f} ms | {ft:10.3f} ms | {fe:11.3f} ms')

    summary = '\n'.join(summary_lines)
    print(summary)
    (OUT_DIR / 'tps_mspt.txt').write_text(summary)

    # Plot
    x = np.arange(len(INSTANCES))
    width = 0.2

    fig, ax = plt.subplots(figsize=(12, 6))
    ax.bar(x - 1.5 * width, mux_tick_vals, width, label='Multiplexer perTick')
    ax.bar(x - 0.5 * width, mux_event1_vals, width, label='Multiplexer onEvent 1/s')
    ax.bar(x + 0.5 * width, fsm_tick_vals, width, label='StateMachine perTick')
    ax.bar(x + 1.5 * width, fsm_event1_vals, width, label='StateMachine onEvent 1/s')

    ax.axhline(BUDGET_MS, color='red', linestyle='--', linewidth=1, label='50 ms tick budget')
    ax.set_xlabel('Active instances (blocks / entities)')
    ax.set_ylabel('MSPT addition (ms)')
    ax.set_title('TPS/MSPT impact of Multiplexer and StateMachine')
    ax.set_xticks(x)
    ax.set_xticklabels([f'{n:,}' for n in INSTANCES])
    ax.set_yscale('log')
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.5)
    fig.tight_layout()
    fig.savefig(OUT_DIR / 'tps_mspt.png', dpi=150)
    print(f'\nChart saved to {OUT_DIR / "tps_mspt.png"}')


if __name__ == '__main__':
    main()
