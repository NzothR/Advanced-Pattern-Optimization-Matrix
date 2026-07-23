# Advanced Pattern Matrix (APM)

A Minecraft 1.7.10 mod for GTNH 2.8.4 that adds the **Advanced Pattern Optimization Matrix** — an AE2 network block that transparently scales processing pattern inputs/outputs at runtime to dramatically reduce CPU dispatch count for large crafting jobs.

## Features

- **Runtime pattern doubling** — multiplies pattern I/O during crafting calculation without modifying stored pattern data
- **Single-shot dispatch** — what would take thousands of CPU dispatches with vanilla AE2 can complete in a handful
- **Plug-and-play** — connect to your ME network and it just works; no GUI, no configuration
- **Automatic multiplier** — computes the optimal multiplier based on your requested quantity
- **Safe ceiling** — never exceeds Integer.MAX_VALUE (2,147,483,647) per single item stack

## How It Works

1. Place an **Advanced Pattern Optimization Matrix** block and connect it to your ME network
2. Request a large quantity of any processing pattern output through an ME terminal
3. The matrix intercepts the crafting calculation and replaces the original pattern with a scaled version — e.g., `1 iron → 1 plate` becomes `123 iron → 123 plates` when you request 123 plates
4. The CPU dispatches a single craft job instead of 123 separate ones

## Building

```bash
./gradlew build
```

The output jar is in `build/libs/`.

## Dependencies

- **AE2**: Applied-Energistics-2-Unofficial rv3-beta-695-GTNH (required)
- **GTNHLib**: 0.6.12+ (for Mixin runtime via UniMixins)

## License

This project is built with the GTNH mod template and is MIT licensed.
