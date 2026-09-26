# ClockIn

Server-side NeoForge mod for tracking administration clock-in time.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- LuckPerms on the server

## Commands

```
/clockin
/clockin in
/clockin out
/clockin leaderboard
/clockin info <player>
```

The root command shows the current player's clock-in status.

## Permissions

| Permission | Purpose |
| --- | --- |
| `clockin.login` | Shows the login clock-in prompt. |
| `clockin.command.status` | Allows `/clockin`. |
| `clockin.command.in` | Allows `/clockin in`. |
| `clockin.command.out` | Allows `/clockin out`. |
| `clockin.command.leaderboard` | Allows `/clockin leaderboard`. |
| `clockin.command.info` | Allows `/clockin info <player>`. |

Permission level 3+ bypasses LuckPerms checks.

## Data

Data is stored in:

```
<world>/clockin.json
```

The schema is compatible with the original KubeJS script, so an existing `clockin.json` can be moved to the world directory and loaded by the mod.

Active sessions are recovered safely: if a player disconnects while clocked in, their current session is added to their total and they are marked as automatically clocked out.

## Build

Requires Java 21.

```bash
gradle clean build
```

The mod JAR is generated under:

```
build/libs/
```
