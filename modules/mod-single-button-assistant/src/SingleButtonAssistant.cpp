#include "Chat.h"
#include "Config.h"
#include "Log.h"
#include "Player.h"
#include "ScriptMgr.h"
#include "Spell.h"
#include "SpellMgr.h"
#include "Timer.h"
#include "WorldSession.h"

#include <array>
#include <mutex>
#include <string>
#include <unordered_map>

namespace
{
    constexpr char const* ADDON_PREFIX = "BygdokSBA";
    constexpr char const* REQUEST = "PRESS";
    constexpr uint32 REQUEST_INTERVAL_MS = 250;
    constexpr uint32 UPDATE_INTERVAL_MS = 250;

    bool IsValidTarget(Player* player, Unit* target)
    {
        return target && target->IsInWorld() && target->IsAlive() && target->GetMap() == player->GetMap() &&
            player->IsValidAttackTarget(target);
    }

    template <size_t N>
    uint32 FindKnownSpell(Player* player, std::array<uint32, N> const& spells)
    {
        for (uint32 spellId : spells)
        {
            if (player->HasSpell(spellId) && sSpellMgr->GetSpellInfo(spellId))
            {
                uint32 highest = spellId;
                for (uint32 next = sSpellMgr->GetNextSpellInChain(spellId); next; next = sSpellMgr->GetNextSpellInChain(next))
                    if (player->HasSpell(next))
                        highest = next;
                return highest;
            }
        }

        return 0;
    }

    struct SpellChoice
    {
        uint32 spellId;
        Unit* target;
    };

    SpellChoice SelectRetributionSpell(Player* player, Unit* target)
    {
        if (player->getClass() != CLASS_PALADIN)
            return {0, nullptr};

        static constexpr std::array<uint32, 6> HAMMER_OF_WRATH = {48806, 48805, 27180, 24239, 24274, 24275};
        static constexpr std::array<uint32, 1> DIVINE_STORM = {53385};
        static constexpr std::array<uint32, 3> CRUSADER_STRIKE = {66003, 56575, 35395};
        static constexpr std::array<uint32, 3> JUDGEMENT = {20271, 53408, 53407};
        static constexpr std::array<uint32, 9> EXORCISM = {48801, 48800, 27138, 10314, 10313, 10312, 5615, 5614, 879};
        static constexpr std::array<uint32, 8> CONSECRATION = {48819, 48818, 27173, 20924, 20923, 20922, 20116, 26573};
        SpellChoice fallback{0, nullptr};
        auto consider = [&](uint32 id, Unit* castTarget) -> SpellChoice
        {
            if (!id)
                return {0, nullptr};
            if (!fallback.spellId)
                fallback = {id, castTarget};
            return player->HasSpellCooldown(id) ? SpellChoice{0, nullptr} : SpellChoice{id, castTarget};
        };
        auto choose = [&](bool contextual) -> SpellChoice
        {
            SpellChoice choice{0, nullptr};
            if (contextual && !target)
                if ((choice = consider(FindKnownSpell(player, JUDGEMENT), target)).spellId)
                    return choice;
            if ((!contextual || (target && target->GetHealthPct() <= 20.0f)) &&
                (choice = consider(FindKnownSpell(player, HAMMER_OF_WRATH), target)).spellId)
                return choice;
            if ((choice = consider(FindKnownSpell(player, DIVINE_STORM), target)).spellId)
                return choice;
            if ((choice = consider(FindKnownSpell(player, CRUSADER_STRIKE), target)).spellId)
                return choice;
            if ((choice = consider(FindKnownSpell(player, JUDGEMENT), target)).spellId)
                return choice;
            if ((!contextual || player->HasAura(59578)) &&
                (choice = consider(FindKnownSpell(player, EXORCISM), target)).spellId)
                return choice;
            return consider(FindKnownSpell(player, CONSECRATION), player);
        };
        SpellChoice choice = choose(true);
        if (choice.spellId)
            return choice;
        if (fallback.spellId)
            return fallback;
        choice = choose(false);
        return choice.spellId ? choice : fallback;
    }

    class SingleButtonAssistantScript : public PlayerScript
    {
    public:
        SingleButtonAssistantScript()
            : PlayerScript("SingleButtonAssistantScript",
                           {PLAYERHOOK_CAN_PLAYER_USE_PRIVATE_CHAT, PLAYERHOOK_ON_LOGOUT, PLAYERHOOK_ON_AFTER_UPDATE})
        {
        }

        bool OnPlayerCanUseChat(Player* player, uint32 /*type*/, uint32 lang, std::string& message,
                                Player* receiver) override
        {
            if (lang != LANG_ADDON || receiver != player)
                return true;

            std::string const prefix = std::string(ADDON_PREFIX) + "\t";
            if (message.compare(0, prefix.size(), prefix) != 0)
                return true;

            if (message.substr(prefix.size()) == REQUEST)
            {
                Send(player, "ACK");
                HandlePress(player);
            }
            else if (message.substr(prefix.size()) == "WATCH")
            {
                {
                    std::lock_guard<std::mutex> lock(stateMutex);
                    State& state = states[player->GetGUID().GetCounter()];
                    state.watching = true;
                    state.hasRecommendation = false;
                    state.hasTarget = false;
                    state.elapsed = 0;
                }
                UpdateRecommendation(player);
            }

            return false;
        }

        void OnPlayerLogout(Player* player) override
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            states.erase(player->GetGUID().GetCounter());
        }

        void OnPlayerAfterUpdate(Player* player, uint32 diff) override
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                auto it = states.find(player->GetGUID().GetCounter());
                if (it == states.end() || !it->second.watching)
                    return;
                if (diff < UPDATE_INTERVAL_MS - it->second.elapsed)
                {
                    it->second.elapsed += diff;
                    return;
                }
                it->second.elapsed = 0;
            }
            UpdateRecommendation(player);
        }

    private:
        struct State
        {
            uint32 lastRequest = 0;
            uint32 elapsed = 0;
            uint32 recommendation = 0;
            uint64 target = 0;
            bool hasRequest = false;
            bool watching = false;
            bool hasRecommendation = false;
            bool hasTarget = false;
        };

        void Send(Player* player, std::string const& payload)
        {
            WorldPacket response;
            ChatHandler::BuildChatPacket(response, CHAT_MSG_WHISPER, LANG_ADDON, player, player,
                                         std::string(ADDON_PREFIX) + "\t" + payload);
            player->GetSession()->SendPacket(&response);
        }

        void UpdateRecommendation(Player* player)
        {
            Unit* target = player->GetSelectedUnit();
            if (!sConfigMgr->GetOption<bool>("SingleButtonAssistant.Enable", true) || !IsValidTarget(player, target))
                target = nullptr;
            uint32 spell = sConfigMgr->GetOption<bool>("SingleButtonAssistant.Enable", true) ?
                SelectRetributionSpell(player, target).spellId : 0;
            uint64 targetId = target ? target->GetGUID().GetRawValue() : 0;
            bool sendSpell = false;
            bool sendTarget = false;
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                auto it = states.find(player->GetGUID().GetCounter());
                if (it == states.end() || !it->second.watching)
                    return;
                State& state = it->second;
                sendSpell = !state.hasRecommendation || state.recommendation != spell;
                sendTarget = !state.hasTarget || state.target != targetId;
                state.recommendation = spell;
                state.target = targetId;
                state.hasRecommendation = true;
                state.hasTarget = true;
            }
            if (sendSpell)
                Send(player, "SPELL:" + std::to_string(spell));
            if (sendTarget)
                Send(player, "TARGET:" + (target ? target->GetName() : std::string()));
        }

        void HandlePress(Player* player)
        {
            uint32 now = getMSTime();
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                State& state = states[player->GetGUID().GetCounter()];
                if (state.hasRequest && now - state.lastRequest < REQUEST_INTERVAL_MS)
                    return;
                state.lastRequest = now;
                state.hasRequest = true;
            }

            if (!sConfigMgr->GetOption<bool>("SingleButtonAssistant.Enable", true) || player->getClass() != CLASS_PALADIN)
                return;

            Unit* target = player->GetSelectedUnit();
            if (!IsValidTarget(player, target))
            {
                UpdateRecommendation(player);
                return;
            }

            SpellChoice choice = SelectRetributionSpell(player, target);
            if (choice.spellId && choice.target && !player->HasSpellCooldown(choice.spellId))
            {
                SpellCastResult result = player->CastSpell(choice.target, choice.spellId, TRIGGERED_SILENT_NORMAL_CAST);
                if (result != SPELL_CAST_OK)
                    LOG_DEBUG("module", "Single Button Assistant spell {} failed with result {}", choice.spellId, uint32(result));
            }
            UpdateRecommendation(player);
        }

        std::mutex stateMutex;
        std::unordered_map<uint64, State> states;
    };
}

void AddSC_SingleButtonAssistant()
{
    new SingleButtonAssistantScript();
}
