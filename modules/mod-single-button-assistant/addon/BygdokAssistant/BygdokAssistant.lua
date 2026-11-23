local PREFIX = "BygdokSBA"
local fallbackIcon = "Interface\\Icons\\INV_Misc_QuestionMark"
local actionIcon = fallbackIcon
local recommendedSpell = 0
local awaitingResponse = false
local responseDeadline = 0
local elapsed = 0
local moving = false
local suppressClickUntil = 0
local actionBars = {
    "ActionButton", "BonusActionButton", "MultiBarBottomLeftButton",
    "MultiBarBottomRightButton", "MultiBarLeftButton", "MultiBarRightButton",
}

local function IsAssistantMacro(body)
    if not body then
        return false
    end
    for line in string.gmatch(body .. "\n", "([^\n]*)\n") do
        if string.match(line, "^%s*/click%s+BygdokAssistantAction%s*$") then
            return true
        end
    end
    return false
end

local function UpdateActionIcon(actionButton)
    if not actionButton or not actionButton.action then
        return
    end
    local actionType, macroId = GetActionInfo(actionButton.action)
    if actionType ~= "macro" or not macroId or not IsAssistantMacro(GetMacroBody(macroId)) then
        return
    end
    local name = actionButton:GetName()
    local texture = name and _G[name .. "Icon"]
    if texture then
        texture:SetTexture(actionIcon)
        texture:Show()
    end
end

local function UpdateAllActionIcons()
    for _, bar in ipairs(actionBars) do
        for index = 1, 12 do
            UpdateActionIcon(_G[bar .. index])
        end
    end
end
hooksecurefunc("ActionButton_Update", UpdateActionIcon)

local button = CreateFrame("Button", "BygdokSingleButtonAssistant", UIParent)
button:SetWidth(40)
button:SetHeight(40)
button:SetPoint("CENTER", UIParent, "CENTER", 0, -180)
button:SetMovable(true)
button:EnableMouse(true)
button:EnableMouseWheel(true)
button:RegisterForClicks("LeftButtonUp")
button:RegisterForDrag("LeftButton")
local icon = button:CreateTexture(nil, "ARTWORK")
icon:SetAllPoints(button)
icon:SetTexture(fallbackIcon)
local cooldown = CreateFrame("Cooldown", "BygdokAssistantCooldown", button, "CooldownFrameTemplate")
cooldown:SetAllPoints(button)

local function SavePosition()
    local x, y = button:GetCenter()
    local parentX, parentY = UIParent:GetCenter()
    if x and y and parentX and parentY then
        BygdokAssistantSettings.x = x - parentX
        BygdokAssistantSettings.y = y - parentY
    end
end

button:SetScript("OnDragStart", function(self)
    if IsShiftKeyDown() then
        moving = true
        self:StartMoving()
    end
end)
button:SetScript("OnDragStop", function(self)
    if moving then
        self:StopMovingOrSizing()
        moving = false
        suppressClickUntil = GetTime() + 0.2
        SavePosition()
    end
end)
button:SetScript("OnMouseWheel", function(self, delta)
    if IsShiftKeyDown() then
        local size = math.max(24, math.min(100, self:GetWidth() + delta * 4))
        self:SetWidth(size)
        self:SetHeight(size)
        BygdokAssistantSettings.size = size
    end
end)

local function SendPress()
    if awaitingResponse then
        return
    end
    awaitingResponse = true
    responseDeadline = GetTime() + 2
    SendAddonMessage(PREFIX, "PRESS", "WHISPER", UnitName("player"))
end
button:SetScript("OnClick", function()
    if not IsShiftKeyDown() and not moving and GetTime() >= suppressClickUntil then
        SendPress()
    end
end)
local action = CreateFrame("Button", "BygdokAssistantAction", UIParent)
action:SetWidth(1)
action:SetHeight(1)
action:SetPoint("TOPLEFT", UIParent, "TOPLEFT", -100, 100)
action:SetAlpha(0)
action:RegisterForClicks("LeftButtonUp")
action:SetScript("OnClick", SendPress)
button:SetScript("OnEnter", function(self)
    GameTooltip:SetOwner(self, "ANCHOR_RIGHT")
    if recommendedSpell ~= 0 and GetSpellInfo(recommendedSpell) then
        GameTooltip:SetHyperlink("spell:" .. recommendedSpell)
    else
        GameTooltip:SetText("Single Button Assistant")
    end
    GameTooltip:Show()
end)
button:SetScript("OnLeave", function()
    GameTooltip:Hide()
end)

local function UpdateVisuals()
    if recommendedSpell == 0 then
        icon:SetVertexColor(1, 1, 1)
        CooldownFrame_SetTimer(cooldown, 0, 0, 0)
        return
    end
    local name = GetSpellInfo(recommendedSpell)
    local start, duration, enabled = GetSpellCooldown(recommendedSpell)
    CooldownFrame_SetTimer(cooldown, start or 0, duration or 0, enabled or 0)
    local usable, noMana = IsUsableSpell(recommendedSpell)
    local inRange = name and UnitExists("target") and IsSpellInRange(name, "target")
    if inRange == 0 then
        icon:SetVertexColor(1, 0.1, 0.1)
    elseif noMana then
        icon:SetVertexColor(0.5, 0.5, 1)
    elseif not usable then
        icon:SetVertexColor(0.4, 0.4, 0.4)
    else
        icon:SetVertexColor(1, 1, 1)
    end
end

local listener = CreateFrame("Frame")
listener:RegisterEvent("ADDON_LOADED")
listener:RegisterEvent("CHAT_MSG_ADDON")
listener:RegisterEvent("PLAYER_ENTERING_WORLD")
listener:RegisterEvent("SPELL_UPDATE_COOLDOWN")
listener:RegisterEvent("SPELL_UPDATE_USABLE")
listener:RegisterEvent("PLAYER_TARGET_CHANGED")
listener:SetScript("OnEvent", function(self, event, prefix, message, channel, sender)
    if event == "ADDON_LOADED" and prefix == "BygdokAssistant" then
        BygdokAssistantSettings = BygdokAssistantSettings or {}
        local size = tonumber(BygdokAssistantSettings.size) or 40
        size = math.max(24, math.min(100, size))
        button:SetWidth(size)
        button:SetHeight(size)
        if type(BygdokAssistantSettings.x) == "number" and type(BygdokAssistantSettings.y) == "number" then
            button:ClearAllPoints()
            button:SetPoint("CENTER", UIParent, "CENTER", BygdokAssistantSettings.x, BygdokAssistantSettings.y)
        end
        return
    end
    if event == "PLAYER_ENTERING_WORLD" then
        recommendedSpell = 0
        actionIcon = fallbackIcon
        icon:SetTexture(actionIcon)
        UpdateAllActionIcons()
        UpdateVisuals()
        SendAddonMessage(PREFIX, "WATCH", "WHISPER", UnitName("player"))
        return
    end
    if event ~= "CHAT_MSG_ADDON" then
        UpdateVisuals()
        return
    end
    if prefix ~= PREFIX or channel ~= "WHISPER" or sender ~= UnitName("player") then
        return
    end
    if message == "ACK" then
        awaitingResponse = false
        return
    end
    local spellId = string.match(message, "^SPELL:(%d+)$")
    if spellId then
        recommendedSpell = tonumber(spellId)
        local _, _, texture = GetSpellInfo(recommendedSpell)
        actionIcon = texture or fallbackIcon
        icon:SetTexture(actionIcon)
        UpdateAllActionIcons()
        UpdateVisuals()
    end
end)
listener:SetScript("OnUpdate", function(self, diff)
    if awaitingResponse and GetTime() >= responseDeadline then
        awaitingResponse = false
    end
    elapsed = elapsed + diff
    if elapsed >= 0.2 then
        elapsed = 0
        UpdateVisuals()
    end
end)

SLASH_BYGDOKASSISTANT1 = "/sba"
SlashCmdList.BYGDOKASSISTANT = function()
    if button:IsShown() then
        button:Hide()
    else
        button:Show()
    end
end
