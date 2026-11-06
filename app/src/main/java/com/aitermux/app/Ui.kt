@Composable fun LangScreen(back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Bar(t("اللغة", "Language"), back)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GBtn("العربية") { Store.lang = "ar"; Store.save(); back() }
            GBtn("English") { Store.lang = "en"; Store.save(); back() }
        }
    }
}

@Composable fun SettingsScreen(act: MainActivity, back: () -> Unit) {
    var clearChats by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Bar(t("الإعدادات", "Settings"), back)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Text-to-Speech toggle
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                G(t("قراءة الردود صوتياً", "Read responses aloud"), 16, true, modifier = Modifier.weight(1f))
                Switch(checked = Store.speak, onCheckedChange = { Store.speak = it; Store.save() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Gold, checkedTrackColor = Gold.copy(alpha = 0.5f)))
            }
            HorizontalDivider(color = Gold.copy(alpha = 0.4f))
            
            // Clear all chats button
            GBtn(t("مسح كل المحادثات", "Clear all chats")) { clearChats = true }
            
            // App Info / Version
            Spacer(Modifier.height(16.dp))
            G("AI Termux Client v1.0", 12, color = Gold.copy(alpha = 0.7f))
        }
    }
    if (clearChats) {
        Confirm(t("مسح كافة سجلات المحادثات؟", "Delete all chat logs?"),
            { Store.chats.clear(); Store.save(); clearChats = false },
            { clearChats = false })
    }
}