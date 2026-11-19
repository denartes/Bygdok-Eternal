#ifndef MOD_OLLAMA_CHAT_GUILD_H
#define MOD_OLLAMA_CHAT_GUILD_H

#include <string>

class Player;

bool GuildRelationshipsMode();
bool RouteGuildRelationshipMessage(Player* player, std::string const& message);
void RegisterGuildRelationshipScripts();

#endif
