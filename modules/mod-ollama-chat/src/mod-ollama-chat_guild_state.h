#ifndef MOD_OLLAMA_CHAT_GUILD_STATE_H
#define MOD_OLLAMA_CHAT_GUILD_STATE_H

#include <algorithm>
#include <charconv>
#include <cstdint>
#include <limits>
#include <map>
#include <string>
#include <string_view>

namespace GuildRelationships
{
inline size_t MemoryChunkSize(std::string_view chunk, bool hasMore)
{
    if (hasMore && !chunk.empty())
    {
        auto boundary = chunk.rfind('\n');
        if (boundary != chunk.npos)
            return boundary + 1;
    }
    return chunk.size();
}

inline std::string_view RecentDialogue(std::string_view tail, size_t limit)
{
    if (tail.size() <= limit)
        return tail;
    auto boundary = tail.find('\n');
    return boundary == tail.npos ? std::string_view{} : tail.substr(boundary + 1);
}

class RoutingIds
{
public:
    uint32_t Next(uint64_t owner)
    {
        auto& last = lastIds[owner];
        return last == std::numeric_limits<uint32_t>::max() ? 0 : ++last;
    }

private:
    std::map<uint64_t, uint32_t> lastIds;
};

template <typename Sessions>
auto FindRoutedSession(Sessions& sessions, uint64_t owner, uint32_t id)
{
    if (id != 0)
        for (auto it = sessions.lower_bound({owner, 0}); it != sessions.end() && it->first.first == owner; ++it)
            if (it->second.routingId == id)
                return it;
    return sessions.end();
}

inline char AsciiLower(char c)
{
    return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c;
}

inline std::string_view StripSpeakerLabel(std::string_view text, std::string_view name)
{
    if (name.empty() || text.size() <= name.size() || text[name.size()] != ':' ||
        !std::equal(name.begin(), name.end(), text.begin(), [](char a, char b) {
            return AsciiLower(a) == AsciiLower(b);
        }))
        return text;
    text.remove_prefix(name.size() + 1);
    auto first = text.find_first_not_of(' ');
    return first == text.npos ? std::string_view{} : text.substr(first);
}

inline std::string FormatReply(uint32_t id, std::string_view dialogue)
{
    auto reply = "@" + std::to_string(id) + " ";
    reply += dialogue;
    return reply;
}

struct Route
{
    enum Kind { New, Existing, Invalid } kind;
    uint32_t id;
    std::string_view text;
};

inline Route Parse(std::string_view text)
{
    auto first = text.find_first_not_of(" \t");
    if (first == text.npos)
        return {Route::Invalid, 0, {}};
    text.remove_prefix(first);
    if (text.front() != '@')
        return {Route::New, 0, text};

    auto separator = text.find_first_of(" \t");
    if (separator == text.npos)
        return {Route::Invalid, 0, {}};
    uint32_t id = 0;
    auto const end = text.data() + separator;
    auto result = std::from_chars(text.data() + 1, end, id);
    auto body = text.find_first_not_of(" \t", separator);
    if (result.ec != std::errc{} || result.ptr != end || !id || body == text.npos)
        return {Route::Invalid, 0, {}};
    return {Route::Existing, id, text.substr(body)};
}
}
#endif
