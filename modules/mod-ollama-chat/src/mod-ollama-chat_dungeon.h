#ifndef MOD_OLLAMA_CHAT_DUNGEON_H
#define MOD_OLLAMA_CHAT_DUNGEON_H

#include "ScriptMgr.h"
#include <string>

class Player;
class Creature;
class Map;

bool DungeonChatOwnsParty(Player* player);
bool DungeonChatHandleMessage(Player* player, std::string const& message);
void DungeonChatDeath(Player* player);
void DungeonChatBossKill(Player* player, Creature* creature);

class DungeonChatMapScript : public AllMapScript
{
public:
    DungeonChatMapScript();
    void OnMapUpdate(Map* map, uint32 diff) override;
    void OnDestroyMap(Map* map) override;
};

class DungeonChatGroupScript : public GroupScript
{
public:
    DungeonChatGroupScript();
    void OnAddMember(Group* group, ObjectGuid guid) override;
    void OnRemoveMember(Group* group, ObjectGuid guid, RemoveMethod method, ObjectGuid kicker, char const* reason) override;
    void OnDisband(Group* group) override;
};

class DungeonChatEncounterScript : public GlobalScript
{
public:
    DungeonChatEncounterScript();
    void OnAfterUpdateEncounterState(Map* map, EncounterCreditType type, uint32 creditEntry,
        Unit* source, Difficulty difficulty, std::list<DungeonEncounter const*> const* encounters,
        uint32 dungeonCompleted, bool updated) override;
};

#endif
