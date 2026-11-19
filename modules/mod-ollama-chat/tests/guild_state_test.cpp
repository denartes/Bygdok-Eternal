#include "../src/mod-ollama-chat_guild_state.h"
#include <cassert>

int main()
{
    using namespace GuildRelationships;
    auto newSession = Parse("  Thrain, up for ICC?");
    assert(newSession.kind == Route::New && newSession.text == "Thrain, up for ICC?");

    auto followUp = Parse(" @2 \tWhat time?");
    assert(followUp.kind == Route::Existing && followUp.id == 2 && followUp.text == "What time?");
    auto largest = Parse("@4294967295 test");
    assert(largest.kind == Route::Existing && largest.id == UINT32_MAX);

    for (auto invalid : {"", " ", "@", "@1", "@0 test", "@1 ", "@+1 test",
        "@-1 test", "@1abc test", "@4294967296 test", "@Thrain hi"})
        assert(Parse(invalid).kind == Route::Invalid);

    assert(MemoryChunkSize("one\n two", true) == 4);
    assert(MemoryChunkSize("one\n two", false) == 8);
    assert(MemoryChunkSize("", false) == 0);
    assert(RecentDialogue("ne\ntwo\n", 6) == "two\n");
    assert(RecentDialogue("one\ntwo\n", 8) == "one\ntwo\n");

    RoutingIds previousRun;
    assert(previousRun.Next(17) == 1);
    assert(previousRun.Next(17) == 2);
    RoutingIds currentRun;
    assert(currentRun.Next(17) == 1);
    assert(currentRun.Next(42) == 1);

    struct ActiveSession { uint32_t routingId; };
    using Key = std::pair<uint64_t, uint32_t>;
    std::map<Key, ActiveSession> sessions{{{17, 4}, {1}}, {{42, 7}, {1}}, {{42, 8}, {2}}};
    assert(FindRoutedSession(sessions, 17, 1)->first == Key(17, 4));
    assert(FindRoutedSession(sessions, 42, 1)->first == Key(42, 7));
    assert(FindRoutedSession(sessions, 17, followUp.id) == sessions.end());
    assert(followUp.text == "What time?");
    sessions.erase(Key(17, 4));
    assert(FindRoutedSession(sessions, 17, 1) == sessions.end());
    assert(currentRun.Next(17) == 2);

    assert(FormatReply(1, StripSpeakerLabel("Bygdok: Ah, hello there!", "Bygdok")) == "@1 Ah, hello there!");
    assert(StripSpeakerLabel("bygdok:  Hello!", "Bygdok") == "Hello!");
    assert(StripSpeakerLabel("Bygdok:", "Bygdok").empty());
    assert(StripSpeakerLabel("Hello from Bygdok: ready to go?", "Bygdok") == "Hello from Bygdok: ready to go?");
    assert(StripSpeakerLabel("Elira: ready to go?", "Bygdok") == "Elira: ready to go?");
    assert(StripSpeakerLabel("Bygdokson: hello!", "Bygdok") == "Bygdokson: hello!");
}
