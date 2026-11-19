#ifndef MOD_OLLAMA_CHAT_DUNGEON_STATE_H
#define MOD_OLLAMA_CHAT_DUNGEON_STATE_H

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <deque>
#include <iterator>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

namespace DungeonChat
{
using Clock = std::chrono::steady_clock;
constexpr std::size_t MaxHistory = 10;
constexpr std::size_t MaxQueue = 8;
constexpr auto GracePeriod = std::chrono::seconds(60);

struct Key
{
    std::uint64_t group;
    std::uint32_t map;
    std::uint32_t instance;

    bool operator==(Key const& other) const
    {
        return group == other.group && map == other.map && instance == other.instance;
    }
};

struct Session
{
    std::uint64_t generation = 0;
    std::uint64_t request = 0;
    std::vector<std::uint64_t> roster;
    std::deque<std::string> events;
    std::deque<std::string> conversation;
    std::unordered_set<std::uint64_t> defeated;
    Clock::time_point lastPresent;
    Clock::time_point lastSpoken = Clock::time_point::min();
    std::uint64_t lastBot = 0;
    bool completed = false;
};

struct Request
{
    Key key;
    std::uint64_t generation;
    std::uint64_t serial;
    std::uint64_t bot;
    std::uint64_t actor;
    bool direct;
    Clock::time_point expires;
    int timeout;
    std::string prompt;
};

inline void Append(std::deque<std::string>& history, std::string text)
{
    history.push_back(std::move(text));
    while (history.size() > MaxHistory)
        history.pop_front();
}

inline bool Current(Session const& session, Request const& request, Clock::time_point now)
{
    return session.generation == request.generation && session.request == request.serial &&
        now < request.expires && now - session.lastPresent <= GracePeriod;
}

inline bool Enqueue(std::deque<Request>& queue, Request request)
{
    auto pending = std::find_if(queue.begin(), queue.end(),
        [&](Request const& item) { return item.key == request.key; });
    if (pending != queue.end())
    {
        if (!request.direct && pending->direct)
            return false;
        queue.erase(pending);
    }
    if (queue.size() >= MaxQueue)
    {
        if (!request.direct)
            return false;
        auto optional = std::find_if(queue.rbegin(), queue.rend(),
            [](Request const& item) { return !item.direct; });
        if (optional == queue.rend())
            return false;
        queue.erase(std::next(optional).base());
    }
    if (request.direct)
        queue.push_front(std::move(request));
    else
        queue.push_back(std::move(request));
    return true;
}
}

#endif
