#include "../src/SingleButtonAssistant.cpp"

#include <cassert>

int main()
{
    SingleButtonAssistantScript script;
    Player player;
    player.guid = {1};
    Unit enemy;
    enemy.hostile = false;
    enemy.guid = {2};
    player.known = {20271};
    std::string watch = "BygdokSBA\tWATCH";
    std::string press = "BygdokSBA\tPRESS";

    script.OnPlayerCanUseChat(&player, CHAT_MSG_WHISPER, LANG_ADDON, watch, &player);
    assert(player.session.packets[0] == "BygdokSBA\tSPELL:20271");
    assert(player.session.packets[1] == "BygdokSBA\tTARGET:");
    player.cooldown.insert(20271);
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.size() == 2);
    script.OnPlayerCanUseChat(&player, CHAT_MSG_WHISPER, LANG_ADDON, press, &player);
    assert(player.castSpell == 0 && player.selected == nullptr);
    assert(player.session.notifications.empty());

    player.selected = &enemy;
    player.cooldown.clear();
    fakeTime = 250;
    script.OnPlayerCanUseChat(&player, CHAT_MSG_WHISPER, LANG_ADDON, press, &player);
    assert(player.castSpell == 20271 && player.castTarget == &enemy);
    assert(player.selected == &enemy);

    player.known.insert(35395);
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:35395");
    player.cooldown.insert(35395);
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:20271");
    player.cooldown.insert(20271);
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:35395");

    player.known.insert(48805);
    enemy.health = 20;
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:48805");
    spellMgr.nextRanks[48805] = 900001;
    player.known.insert(900001);
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:900001");
    player.cooldown.insert(900001);
    player.cooldown.erase(20271);
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:20271");
    player.mana = 0;
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:20271");

    Unit friendly;
    friendly.attackable = false;
    friendly.guid = {3};
    player.selected = &friendly;
    player.castSpell = 0;
    fakeTime = 500;
    script.OnPlayerCanUseChat(&player, CHAT_MSG_WHISPER, LANG_ADDON, press, &player);
    assert(player.selected == &friendly && player.castSpell == 0);
    assert(player.session.notifications.empty());
    player.selected = &enemy;
    enemy.alive = false;
    fakeTime = 750;
    script.OnPlayerCanUseChat(&player, CHAT_MSG_WHISPER, LANG_ADDON, press, &player);
    assert(player.selected == &enemy && player.castSpell == 0);
    enemy.alive = true;
    player.castResult = SPELL_FAILED;
    fakeTime = 1000;
    script.OnPlayerCanUseChat(&player, CHAT_MSG_WHISPER, LANG_ADDON, press, &player);
    assert(player.castSpell == 20271 && player.session.notifications.empty());

    player.selected = nullptr;
    player.known = {20271};
    player.cooldown = {20271};
    script.OnPlayerAfterUpdate(&player, 250);
    assert(SelectRetributionSpell(&player, nullptr).spellId == 20271);
    std::size_t count = player.session.packets.size();
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.size() == count);
    player.known.clear();
    script.OnPlayerAfterUpdate(&player, 250);
    assert(player.session.packets.back() == "BygdokSBA\tSPELL:0");
    script.OnPlayerLogout(&player);
}
