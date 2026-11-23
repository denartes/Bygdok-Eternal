local frames, sent = {}, {}
local time, shift, hasTarget, range, usable, noMana = 0, false, false, 1, true, false
local start, duration, enabled = 0, 0, 1
local actionSlots, macroBodies = {}, {}
local updateHook
UIParent = {GetCenter = function() return 400, 300 end}
SlashCmdList = {}
GameTooltip = {
    SetOwner = function() end,
    SetText = function() end,
    SetHyperlink = function(self, link) self.link = link end,
    Show = function() end,
    Hide = function() end
}

function CreateFrame(kind, name)
    local frame = {scripts = {}, events = {}, shown = true, width = 0, height = 0, name = name}
    function frame:SetWidth(value) self.width = value end
    function frame:GetWidth() return self.width end
    function frame:SetHeight(value) self.height = value end
    function frame:SetPoint(...) self.point = {...} end
    function frame:ClearAllPoints() self.point = nil end
    function frame:SetAllPoints() end
    function frame:SetAlpha() end
    function frame:SetMovable() end
    function frame:EnableMouse() end
    function frame:EnableMouseWheel() end
    function frame:RegisterForClicks() end
    function frame:RegisterForDrag() end
    function frame:RegisterEvent(event) self.events[event] = true end
    function frame:SetScript(name, callback) self.scripts[name] = callback end
    function frame:StartMoving() self.moved = true end
    function frame:StopMovingOrSizing() end
    function frame:GetCenter() return 430, 250 end
    function frame:IsShown() return self.shown end
    function frame:Show() self.shown = true end
    function frame:Hide() self.shown = false end
    function frame:CreateTexture()
        local texture = {}
        function texture:SetAllPoints() end
        function texture:SetTexture(value) self.value = value end
        function texture:SetVertexColor(r, g, b) self.color = {r, g, b} end
        self.texture = texture
        return texture
    end
    table.insert(frames, frame)
    return frame
end
function CooldownFrame_SetTimer(frame, s, d, e) frame.timer = {s, d, e} end
function GetActionInfo(slot) if actionSlots[slot] then return "macro", actionSlots[slot] end end
function GetMacroBody(id) return macroBodies[id] end
function hooksecurefunc(name, callback) assert(name == "ActionButton_Update"); updateHook = callback end
function GetTime() return time end
function UnitName() return "Tester" end
function UnitExists() return hasTarget end
function IsShiftKeyDown() return shift end
function IsSpellInRange() return range end
function IsUsableSpell() return usable, noMana end
function GetSpellCooldown() return start, duration, enabled end
function SendAddonMessage(prefix, message, channel, target) table.insert(sent, {prefix, message, channel, target}) end
function GetSpellInfo(id)
    if id == 20271 then return "Judgement", nil, "judgement-icon" end
    if id == 35395 then return "Crusader Strike", nil, "crusader-icon" end
end

dofile(addonPath or arg[1])
local button, cooldown, action, listener = frames[1], frames[2], frames[3], frames[4]
local barButton = {action = 1, GetName = function() return "ActionButton1" end}
local barIcon = {value = "original", SetTexture = function(self, value) self.value = value end,
    Show = function(self) self.shown = true end}
_G.ActionButton1 = barButton
_G.ActionButton1Icon = barIcon
actionSlots[1] = 4
macroBodies[4] = "#showtooltip\n/cleartarget [dead][help]\n/targetenemy [noexists][noharm]\n/startattack\n/click BygdokAssistantAction"
local event = listener.scripts.OnEvent
event(listener, "ADDON_LOADED", "BygdokAssistant")
assert(button.width == 40 and button.height == 40)
event(listener, "PLAYER_ENTERING_WORLD")
assert(sent[1][2] == "WATCH")
event(listener, "CHAT_MSG_ADDON", "BygdokSBA", "SPELL:20271", "WHISPER", "Tester")
assert(button.texture.value == "judgement-icon" and barIcon.value == "judgement-icon")
start, duration = 10, 8
event(listener, "SPELL_UPDATE_COOLDOWN")
assert(cooldown.timer[1] == 10 and cooldown.timer[2] == 8)
assert(button.texture.value == "judgement-icon")
hasTarget, range = true, 0
event(listener, "PLAYER_TARGET_CHANGED")
assert(button.texture.color[1] == 1 and button.texture.color[2] == 0.1)
range, usable, noMana = 1, false, true
event(listener, "SPELL_UPDATE_USABLE")
assert(button.texture.color[3] == 1 and button.texture.color[1] == 0.5)
usable, noMana = true, false
event(listener, "SPELL_UPDATE_USABLE")
assert(button.texture.color[1] == 1 and button.texture.color[2] == 1)
event(listener, "CHAT_MSG_ADDON", "BygdokSBA", "SPELL:35395", "WHISPER", "Other")
assert(button.texture.value == "judgement-icon")
event(listener, "CHAT_MSG_ADDON", "BygdokSBA", "SPELL:35395", "WHISPER", "Tester")
assert(button.texture.value == "crusader-icon" and barIcon.value == "crusader-icon")
button.scripts.OnEnter(button)
assert(GameTooltip.link == "spell:35395")
macroBodies[4] = "/cast Judgement"
barIcon.value = "unrelated"
updateHook(barButton)
assert(barIcon.value == "unrelated")

button.scripts.OnClick()
assert(sent[2][2] == "PRESS")
event(listener, "CHAT_MSG_ADDON", "BygdokSBA", "ACK", "WHISPER", "Tester")
shift = true
button.scripts.OnClick()
assert(#sent == 2)
button.scripts.OnDragStart(button)
button.scripts.OnDragStop(button)
assert(BygdokAssistantSettings.x == 30 and BygdokAssistantSettings.y == -50)
button.scripts.OnMouseWheel(button, 1)
assert(button.width == 44 and BygdokAssistantSettings.size == 44)
shift = false
button.scripts.OnClick()
assert(#sent == 2)
SlashCmdList.BYGDOKASSISTANT()
assert(not button:IsShown())
action.scripts.OnClick()
assert(sent[3][2] == "PRESS")
time = 3
listener.scripts.OnUpdate(listener, 0.2)
assert(#sent == 3)
button.scripts.OnClick()
assert(sent[4][2] == "PRESS")
event(listener, "ADDON_LOADED", "BygdokAssistant")
assert(button.width == 44 and button.point[4] == 30 and button.point[5] == -50)
