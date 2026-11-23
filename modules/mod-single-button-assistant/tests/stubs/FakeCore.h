#pragma once

#include <cstdint>
#include <initializer_list>
#include <list>
#include <map>
#include <set>
#include <string>
#include <type_traits>
#include <vector>

using uint32 = std::uint32_t;
using uint64 = std::uint64_t;

constexpr uint32 CLASS_PALADIN = 2;
constexpr uint32 LANG_ADDON = 0xffffffff;
constexpr uint32 CHAT_MSG_WHISPER = 1;
constexpr int PLAYERHOOK_CAN_PLAYER_USE_PRIVATE_CHAT = 1;
constexpr int PLAYERHOOK_ON_LOGOUT = 2;
constexpr int PLAYERHOOK_ON_AFTER_UPDATE = 3;
constexpr uint32 CHEAT_POWER = 1;
constexpr uint32 MAX_POWERS = 7;
enum Powers { POWER_MANA };
constexpr int PATHFIND_NORMAL = 1;
constexpr int PATHFIND_SHORTCUT = 2;
constexpr int PATHFIND_INCOMPLETE = 4;
constexpr int PATHFIND_NOPATH = 8;
constexpr int PATHFIND_NOT_USING_PATH = 16;
constexpr int PATHFIND_SHORT = 32;
constexpr int PATHFIND_FARFROMPOLY = 192;

enum SpellCastResult { SPELL_CAST_OK, SPELL_FAILED };
enum TriggerCastFlags { TRIGGERED_NONE = 0, TRIGGERED_FULL_MASK = 0x0007ffff, TRIGGERED_SILENT_NORMAL_CAST = 0x00800000 };
static_assert((TRIGGERED_SILENT_NORMAL_CAST & TRIGGERED_FULL_MASK) == 0);

struct ObjectGuid
{
    uint64 value = 0;
    uint64 GetCounter() const { return value; }
    uint64 GetRawValue() const { return value; }
    static ObjectGuid const Empty;
};
inline ObjectGuid const ObjectGuid::Empty{};
using Guid = ObjectGuid;

struct WorldPacket
{
    std::string message;
};

struct WorldSession
{
    std::vector<std::string> packets;
    std::vector<std::string> notifications;
    void SendPacket(WorldPacket* packet) { packets.push_back(packet->message); }
    void SendNotification(char const* message) { notifications.emplace_back(message); }
};

struct Unit
{
    bool inWorld = true;
    bool alive = true;
    int map = 1;
    float health = 100.0f;
    float distance = 1.0f;
    bool hostile = true;
    bool attackable = true;
    bool visible = true;
    bool los = true;
    int pathType = PATHFIND_NORMAL;
    ObjectGuid guid{2};
    std::string name = "enemy";
    bool IsInWorld() const { return inWorld; }
    bool IsAlive() const { return alive; }
    int GetMap() const { return map; }
    float GetHealthPct() const { return health; }
    bool IsHostileTo(Unit const*) const { return hostile; }
    ObjectGuid GetGUID() const { return guid; }
    std::string const& GetName() const { return name; }
    float GetPositionX() const { return distance; }
    float GetPositionY() const { return 0; }
    float GetPositionZ() const { return 0; }
};

struct SpellInfo
{
    uint32 PowerType = POWER_MANA;
    int cost = 0;
    uint32 GetSchoolMask() const { return 0; }
    int CalcPowerCost(Unit*, uint32) const { return cost; }
};

struct SpellMgr
{
    std::map<uint32, SpellInfo> spells;
    std::map<uint32, uint32> nextRanks;
    SpellInfo const* GetSpellInfo(uint32 id) { return &spells[id]; }
    uint32 GetNextSpellInChain(uint32 id)
    {
        auto it = nextRanks.find(id);
        return it == nextRanks.end() ? 0 : it->second;
    }
};
inline SpellMgr spellMgr;
inline SpellMgr* sSpellMgr = &spellMgr;

struct Player : Unit
{
    uint32 playerClass = CLASS_PALADIN;
    bool validTarget = true;
    bool inMelee = false;
    bool inStormRange = false;
    bool artOfWar = false;
    bool inCombatWithTarget = false;
    bool cheatPower = false;
    uint32 mana = 100;
    Unit* selected = nullptr;
    std::vector<Unit*> nearby;
    std::set<uint32> known;
    std::set<uint32> cooldown;
    WorldSession session;
    uint32 castSpell = 0;
    Unit* castTarget = nullptr;
    SpellCastResult castResult = SPELL_CAST_OK;
    bool cooldownOnCast = false;

    uint32 getClass() const { return playerClass; }
    bool IsValidAttackTarget(Unit* target) const { return validTarget && target->attackable; }
    bool IsHostileTo(Unit const* target) const { return target->hostile; }
    bool IsInCombatWith(Unit*) const { return inCombatWithTarget; }
    bool CanSeeOrDetect(Unit* target) const { return target->visible; }
    bool IsWithinLOSInMap(Unit* target) const { return target->los; }
    float GetDistance(Unit* target) const { return target->distance; }
    bool IsWithinMeleeRange(Unit*) const { return inMelee; }
    bool IsWithinCombatRange(Unit*, float range) const { return range == 8.0f && inStormRange; }
    bool HasSpell(uint32 id) const { return known.count(id) != 0; }
    bool HasSpellCooldown(uint32 id) const { return cooldown.count(id) != 0; }
    bool HasAura(uint32 id) const { return id == 59578 && artOfWar; }
    Unit* GetSelectedUnit() { return selected; }
    void SetSelection(ObjectGuid target)
    {
        selected = nullptr;
        for (Unit* unit : nearby)
            if (unit->GetGUID().value == target.value)
                selected = unit;
    }
    bool GetCommandStatus(uint32) const { return cheatPower; }
    uint32 GetPower(Powers) const { return mana; }
    WorldSession* GetSession() { return &session; }
    SpellCastResult CastSpell(Unit* target, uint32 spell, TriggerCastFlags flags)
    {
        if (flags != TRIGGERED_SILENT_NORMAL_CAST)
            return SPELL_FAILED;
        castTarget = target;
        castSpell = spell;
        if (castResult == SPELL_CAST_OK && cooldownOnCast)
            cooldown.insert(spell);
        return castResult;
    }
};

struct ConfigMgr
{
    bool enabled = true;
    float range = 30;
    template <typename T> T GetOption(char const*, T) const
    {
        if constexpr (std::is_same_v<T, bool>)
            return enabled;
        else
            return range;
    }
};

inline ConfigMgr configMgr;
inline ConfigMgr* sConfigMgr = &configMgr;
inline uint32 fakeTime = 0;
inline uint32 getMSTime() { return fakeTime; }
#define LOG_ERROR(...) ((void)0)
#define LOG_DEBUG(...) ((void)0)

namespace Acore
{
    struct AnyUnfriendlyUnitInObjectRangeCheck
    {
        AnyUnfriendlyUnitInObjectRangeCheck(Player*, Player*, float range) : range(range) {}
        bool operator()(Unit* unit) const { return unit->alive && unit->distance <= range; }
        float range;
    };
    template <typename Check> struct UnitListSearcher
    {
        UnitListSearcher(Player*, std::list<Unit*>& result, Check& check) : result(result), check(check) {}
        std::list<Unit*>& result;
        Check& check;
    };
}
namespace Cell
{
    template <typename Searcher> void VisitObjects(Player* player, Searcher& searcher, float)
    {
        for (Unit* unit : player->nearby)
            if (searcher.check(unit))
                searcher.result.push_back(unit);
    }
}
struct PathGenerator
{
    explicit PathGenerator(Player* player) : player(player) {}
    bool CalculatePath(float x, float, float)
    {
        for (Unit* unit : player->nearby)
            if (unit->distance == x)
            {
                type = unit->pathType;
                return true;
            }
        return false;
    }
    int GetPathType() const { return type; }
    Player* player;
    int type = PATHFIND_NOPATH;
};

struct ChatHandler
{
    explicit ChatHandler(WorldSession* session) : session(session) {}
    void SendNotification(char const* message) { session->SendNotification(message); }
    static void BuildChatPacket(WorldPacket& packet, uint32, uint32, Player*, Player*, std::string const& message)
    {
        packet.message = message;
    }
    WorldSession* session;
};

class PlayerScript
{
public:
    PlayerScript(char const*, std::initializer_list<int>) {}
    virtual ~PlayerScript() = default;
    virtual bool OnPlayerCanUseChat(Player*, uint32, uint32, std::string&, Player*) { return true; }
    virtual void OnPlayerLogout(Player*) {}
    virtual void OnPlayerAfterUpdate(Player*, uint32) {}
};
