#include "../src/mod-ollama-chat_dungeon_state.h"
#include <cassert>

using namespace DungeonChat;

int main()
{
    auto now = Clock::now();
    Key key{1, 2, 3};
    Session session;
    session.generation = 4;
    session.request = 5;
    session.lastPresent = now;
    Request request{key, 4, 5, 6, 7, false, now + std::chrono::seconds(30), 30, "event"};

    assert(Current(session, request, now));
    assert(!Current(session, request, request.expires));
    request.expires = now + std::chrono::seconds(120);
    assert(Current(session, request, now + GracePeriod));
    assert(!Current(session, request, now + GracePeriod + std::chrono::seconds(1)));
    session.generation++;
    assert(!Current(session, request, now));
    session.generation--;
    session.request++;
    assert(!Current(session, request, now));
    assert(!(key == Key{1, 2, 4}));
    assert(!(key == Key{2, 2, 3}));

    std::deque<std::string> history;
    for (int i = 0; i < 12; ++i)
        Append(history, std::to_string(i));
    assert(history.size() == MaxHistory);
    assert(history.front() == "2" && history.back() == "11");

    std::deque<Request> queue;
    assert(Enqueue(queue, request));
    Request direct = request;
    direct.direct = true;
    direct.serial++;
    assert(Enqueue(queue, direct));
    assert(queue.size() == 1 && queue.front().direct && queue.front().serial == direct.serial);
    assert(!Enqueue(queue, request));
    assert(queue.front().serial == direct.serial);

    Request other = request;
    for (std::uint64_t group = 2; group <= MaxQueue; ++group)
    {
        other.key.group = group;
        assert(Enqueue(queue, other));
    }
    assert(queue.size() == MaxQueue);
    other.key.group = 99;
    assert(!Enqueue(queue, other));
    assert(queue.size() == MaxQueue);
    direct.key.group = 99;
    assert(Enqueue(queue, direct));
    assert(queue.size() == MaxQueue && queue.front().key.group == 99 && queue.front().direct);
    assert(std::none_of(queue.begin(), queue.end(),
        [](Request const& item) { return item.key.group == MaxQueue; }));
}
