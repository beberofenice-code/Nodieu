# Box Push

A Sokoban-style puzzle game for the terminal, written in C++17.

```bash
make && ./boxpush
```

Push every box `[]` onto a goal `<`. You can only push, never pull, so think before you move!

| Key | Action |
| --- | --- |
| Arrows / WASD / hjkl | Move |
| U | Undo |
| R | Restart level |
| N / P | Next / previous level |
| Q or Esc | Quit |

Add levels by appending raw-string entries to `levels.inc`
(`#` wall, `@` player, `$` box, `.` goal, `*` box on goal, `+` player on goal).
