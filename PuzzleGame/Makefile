CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra

boxpush: main.cpp levels.inc
	$(CXX) $(CXXFLAGS) -o $@ main.cpp

clean:
	rm -f boxpush
