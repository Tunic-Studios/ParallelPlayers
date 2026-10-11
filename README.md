# Parallel Players

Adds Terraria-style characters for Minecraft. A character is a player of its own (inventory, experience,
health, statistics, advancements, name and skin) that you can take into any world. Pick a character
before you play and it comes with you or switch to another and pick up where that one left off.

Characters are stored locally, outside any world, so the same character can play in all your
singleplayer worlds and on servers that have the mod installed.

![character_select.png](img/character_select.png)

**Requires Minecraft 26.3, Fabric Loader and Fabric API.**

## Features

### Characters
- Create, rename, select and delete characters from the **Characters** button on the main menu.
- Each character has its own inventory, armour, experience, health, hunger, effects, ender chest,
  statistics and advancements.
- Characters remember where they last were in each world they have joined. A character that has never been to a world starts
  at spawn

### Your account
- Your Minecraft: Java Edition account is always the first entry in the list, and it is what you play as by default.
- Playing as your account works like the game without the mod: each world keeps its own player individual data.
- Your account's data in a world is set aside while a character plays there and is given back when you
  play that world as your account again.

### Custom skins
- Giving a character their own skin: your account's skin, one of the game's default skins, or any skin you may want to import.

### Modes
- Every character is Survival, Creative or Hardcore, chosen when it is created and stays fixed after that.
- A character can only enter worlds of its own kind, so creative progress never leaks into survival for example:
  - **Survival** characters join survival worlds with cheats off
  - **Creative** characters join worlds that are Creative or have cheats on
  - **Hardcore** characters join only hardcore worlds
- Opening a world that does not suit your character takes you to character select, creating a world also offers to make a character for it.

### In-game
- Your character's name is used in chat, tab list, join, death and advancement messages, hovering over it
  shows the player's username.

### Character backups
- Each character's player data is backed up automatically, keeping up to 5 copies by default, this is customizable!

### Servers and LAN (experimental)
- On a server that has the mod, your active character comes with you. Players without the mod join as
  their account.
- The server keeps its own copy of each player's characters and sends every save back to the client.
  Leaving from the pause menu waits for the last save, so the progress is there in singleplayer too.
- If the server's copy is newer than yours (for example, the game closed before the last save arrived),
  the server's copy is used and sent back to you.
- If your active character can't join the server's kind of world, you pick another one while connecting.
- Other players with the mod see your character's name above your head and its skin.

### Settings and permissions
- A settings screen, opened from [Mod Menu](https://modrinth.com/mod/modmenu) with various options to customize parallel players.
- Settings are kept in `config/parallel/client.json` and `config/parallel/server.json`.
- Servers can turn characters or mode rules off in `server.json`, and control who may use them with a
  permissions mod such as [LuckPerms](https://luckperms.net/):

  | Permission                 | Default  | Allows                                          |
  |----------------------------|----------|-------------------------------------------------|
  | `parallel.characters.use`  | everyone | playing as a character                          |
  | `parallel.modes.bypass`    | nobody   | taking a character into a world of another mode |

## Where things are kept

| What                                   | Where                                           |
|----------------------------------------|-------------------------------------------------|
| Your characters                        | `characters/` in the game folder                |
| Which character is selected            | `characters/active_character.txt`               |
| A server's copies of its players' characters | `<world>/parallel/players/` on the server |
| Account data set aside in a world      | `<world>/parallel/accounts/`                    |

## Building

```
./gradlew build
```

The mod jar is written to `build/libs/`. `./gradlew runClient` and `./gradlew runServer` start a
development client and server.

## License

[MIT](LICENSE.md)
