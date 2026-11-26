// Box Push - a Sokoban-style puzzle game for the terminal (C++17, POSIX).
#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>
#include <termios.h>
#include <unistd.h>

static const std::vector<std::string> kLevels = {
#include "levels.inc"
};

struct Pos { int x, y; };

class Game {
public:
    explicit Game(int level) { load(level); }

    void load(int level) {
        level_ = level;
        grid_.clear(); boxes_.clear(); history_.clear();
        moves_ = pushes_ = 0;
        std::vector<std::string> rows;
        std::string cur;
        for (char c : kLevels[level] + "\n") {
            if (c == '\n') { rows.push_back(cur); cur.clear(); } else cur += c;
        }
        while (!rows.empty() && rows.front().empty()) rows.erase(rows.begin());
        while (!rows.empty() && rows.back().empty()) rows.pop_back();
        size_t w = 0;
        for (auto& r : rows) w = std::max(w, r.size());
        for (size_t y = 0; y < rows.size(); ++y) {
            rows[y].resize(w, ' ');
            std::string row(w, ' ');
            for (size_t x = 0; x < w; ++x) {
                char c = rows[y][x];
                if (c == '#') row[x] = '#';
                if (c == '.' || c == '*' || c == '+') row[x] = '.';
                if (c == '$' || c == '*') boxes_.push_back({(int)x, (int)y});
                if (c == '@' || c == '+') player_ = {(int)x, (int)y};
            }
            grid_.push_back(row);
        }
    }

    bool move(int dx, int dy) {
        Pos np{player_.x + dx, player_.y + dy};
        if (wall(np)) return false;
        int bi = boxAt(np);
        int pushed = -1;
        if (bi >= 0) {
            Pos bp{np.x + dx, np.y + dy};
            if (wall(bp) || boxAt(bp) >= 0) return false;
            history_.push_back({player_, bi, boxes_[bi]});
            boxes_[bi] = bp; pushed = bi; ++pushes_;
        } else {
            history_.push_back({player_, -1, {0, 0}});
        }
        (void)pushed;
        player_ = np; ++moves_;
        return true;
    }

    void undo() {
        if (history_.empty()) return;
        auto h = history_.back(); history_.pop_back();
        player_ = h.player;
        if (h.box >= 0) { boxes_[h.box] = h.boxPos; --pushes_; }
        --moves_;
    }

    bool solved() const {
        for (auto& b : boxes_) if (grid_[b.y][b.x] != '.') return false;
        return true;
    }

    std::string render() const {
        std::string out;
        for (int y = 0; y < (int)grid_.size(); ++y) {
            for (int x = 0; x < (int)grid_[y].size(); ++x) {
                char t = grid_[y][x];
                bool goal = t == '.';
                if (t == '#') out += "\x1b[47m  \x1b[0m";
                else if (player_.x == x && player_.y == y) out += goal ? "\x1b[1;33m(\x1b[1;32m)" : "\x1b[1;36m:)\x1b[0m";
                else if (boxAt({x, y}) >= 0) out += goal ? "\x1b[42;30m[]\x1b[0m" : "\x1b[43;30m[]\x1b[0m";
                else if (goal) out += "\x1b[31m <\x1b[0m";
                else out += "  ";
                if (player_.x == x && player_.y == y && goal) out += "\x1b[0m";
            }
            out += "\n";
        }
        return out;
    }

    int level() const { return level_; }
    int moves() const { return moves_; }
    int pushes() const { return pushes_; }

private:
    struct Undo { Pos player; int box; Pos boxPos; };
    bool wall(Pos p) const {
        if (p.y < 0 || p.y >= (int)grid_.size() || p.x < 0 || p.x >= (int)grid_[p.y].size()) return true;
        return grid_[p.y][p.x] == '#';
    }
    int boxAt(Pos p) const {
        for (size_t i = 0; i < boxes_.size(); ++i)
            if (boxes_[i].x == p.x && boxes_[i].y == p.y) return (int)i;
        return -1;
    }
    int level_ = 0, moves_ = 0, pushes_ = 0;
    std::vector<std::string> grid_;
    std::vector<Pos> boxes_;
    Pos player_{0, 0};
    std::vector<Undo> history_;
};

struct RawMode {
    termios old{};
    RawMode() {
        tcgetattr(STDIN_FILENO, &old);
        termios t = old;
        t.c_lflag &= ~(ICANON | ECHO);
        tcsetattr(STDIN_FILENO, TCSANOW, &t);
        std::fputs("\x1b[?25l", stdout);
    }
    ~RawMode() {
        tcsetattr(STDIN_FILENO, TCSANOW, &old);
        std::fputs("\x1b[?25h\x1b[0m\n", stdout);
    }
};

// Returns 'u','d','l','r' for arrows/WASD/hjkl, or the raw character; 0 on EOF.
static int readKey() {
    unsigned char c;
    if (read(STDIN_FILENO, &c, 1) != 1) return 0;
    if (c == 27) {
        unsigned char a, b;
        if (read(STDIN_FILENO, &a, 1) == 1 && a == '[' && read(STDIN_FILENO, &b, 1) == 1) {
            switch (b) { case 'A': return 'w'; case 'B': return 's'; case 'C': return 'd'; case 'D': return 'a'; }
        }
        return 27;
    }
    switch (c) { case 'k': return 'w'; case 'j': return 's'; case 'l': return 'd'; case 'h': return 'a'; }
    return c;
}

static void draw(const Game& g, const std::string& msg) {
    std::printf("\x1b[2J\x1b[H");
    std::printf("\x1b[1m BOX PUSH\x1b[0m  Level %d/%zu   Moves: %d   Pushes: %d\n\n",
                g.level() + 1, kLevels.size(), g.moves(), g.pushes());
    std::fputs(g.render().c_str(), stdout);
    std::printf("\n Push every box [] onto a goal <  (boxes turn green when placed)\n");
    std::printf(" Move: arrows/WASD/hjkl   U: undo   R: restart   N/P: next/prev level   Q: quit\n");
    if (!msg.empty()) std::printf("\n \x1b[1;32m%s\x1b[0m\n", msg.c_str());
    std::fflush(stdout);
}

int main() {
    if (!isatty(STDIN_FILENO)) {
        std::fprintf(stderr, "Box Push needs an interactive terminal.\n");
        return 1;
    }
    RawMode raw;
    int lv = 0;
    Game g(lv);
    std::string msg;
    while (true) {
        draw(g, msg);
        msg.clear();
        int k = readKey();
        if (k == 0 || k == 'q' || k == 'Q' || k == 27) break;
        switch (k) {
            case 'w': case 'W': g.move(0, -1); break;
            case 's': case 'S': g.move(0, 1); break;
            case 'a': case 'A': g.move(-1, 0); break;
            case 'd': case 'D': g.move(1, 0); break;
            case 'u': case 'U': g.undo(); break;
            case 'r': case 'R': g.load(g.level()); break;
            case 'n': case 'N': if (g.level() + 1 < (int)kLevels.size()) g.load(g.level() + 1); break;
            case 'p': case 'P': if (g.level() > 0) g.load(g.level() - 1); break;
        }
        if (g.solved()) {
            bool last = g.level() + 1 == (int)kLevels.size();
            draw(g, "Solved in " + std::to_string(g.moves()) + " moves! " +
                    (last ? "You beat every level - press any key to exit." : "Press any key for the next level."));
            readKey();
            if (last) break;
            g.load(g.level() + 1);
        }
    }
    return 0;
}
