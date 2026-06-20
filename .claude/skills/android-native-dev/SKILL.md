---
name: android-native-dev
description: Android native application development and UI design guide. Covers Material Design 3, Kotlin/Compose development, project configuration, accessibility, and build troubleshooting. Read this before Android native application development.
license: MIT
metadata:
  version: "1.0.0"
  category: mobile
  sources:
    - Material Design 3 Guidelines (material.io)
    - Android Developer Documentation (developer.android.com)
    - Google Play Quality Guidelines
    - WCAG Accessibility Guidelines
---

## 1. Project Scenario Assessment

Before starting development, assess the current project state:

| Scenario | Characteristics | Approach |
|----------|-----------------|----------|
| **Empty Directory** | No files present | Full initialization required, including Gradle Wrapper |
| **Has Gradle Wrapper** | `gradlew` and `gradle/wrapper/` exist | Use `./gradlew` directly for builds |
| **Android Studio Project** | Complete project structure, may lack wrapper | Check wrapper, run `gradle wrapper` if needed |
| **Incomplete Project** | Partial files present | Check missing files, complete configuration |

**Key Principles**:
- Before writing business logic, ensure `./gradlew assembleDebug` succeeds
- If `gradle.properties` is missing, create it first and configure AndroidX

## 2. Project Configuration

### 2.1 gradle.properties

```properties
android.useAndroidX=true
android.enableJetifier=true
org.gradle.parallel=true
kotlin.code.style=official
```

### 2.2 Dependency Declaration Standards

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
}
```

## 3. Kotlin Development Standards

### 3.1 Naming Conventions

| Type | Convention | Example |
|------|------------|---------|
| Class/Interface | PascalCase | `UserRepository`, `MainActivity` |
| Function/Variable | camelCase | `getUserName()`, `isLoading` |
| Constant | SCREAMING_SNAKE | `MAX_RETRY_COUNT` |
| Composable | PascalCase | `@Composable fun UserCard()` |

### 3.2 Null Safety

```kotlin
// ✅
val name = user?.name ?: "Unknown"
user?.let { processUser(it) }
```

### 3.3 Threading & Coroutines

| Operation | Thread |
|-----------|--------|
| UI Updates | `Dispatchers.Main` |
| Network/File I/O | `Dispatchers.IO` |
| Compute Intensive | `Dispatchers.Default` |

```kotlin
viewModelScope.launch {
    _uiState.value = UiState.Loading
    val result = withContext(Dispatchers.IO) { repository.fetchData() }
    _uiState.value = UiState.Success(result)
}
```

### 3.4 Exception Handling

```kotlin
// ✅ In ViewModel
viewModelScope.launch {
    runCatching { repository.loadData() }
        .onSuccess { _uiState.value = UiState.Success(it) }
        .onFailure { _uiState.value = UiState.Error(it.message) }
}
```

### 3.5 Server Response — All Fields Nullable

```kotlin
data class UserResponse(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
)
```

### 3.6 Lambda return pitfall

```kotlin
// ❌ returns from outer function
list.forEach { if (it.isEmpty()) return }
// ✅
list.forEach { if (it.isEmpty()) return@forEach }
```

## 4. Jetpack Compose Standards

### 4.1 @Composable Rules

```kotlin
// ❌ Composable from non-Composable
fun showError(msg: String) { Text(msg) }
// ✅
@Composable fun ErrorMessage(msg: String) { Text(msg) }

// ✅ Suspend in LaunchedEffect
@Composable fun MyScreen() {
    var data by remember { mutableStateOf<Data?>(null) }
    LaunchedEffect(Unit) { data = fetchData() }
}
```

### 4.2 State Management

```kotlin
var count by remember { mutableStateOf(0) }
val isEven by remember { derivedStateOf { count % 2 == 0 } }

// ViewModel
private val _uiState = MutableStateFlow(UiState())
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```

### 4.3 ViewModel in Composable

```kotlin
// ❌ val viewModel = MyViewModel()
// ✅
@Composable fun MyScreen(viewModel: MyViewModel = viewModel()) { }
```

## 5. Resources & Icons

### 5.1 App Icon Sizes

| Directory | Size |
|-----------|------|
| mipmap-mdpi | 48×48 |
| mipmap-hdpi | 72×72 |
| mipmap-xhdpi | 96×96 |
| mipmap-xxhdpi | 144×144 |
| mipmap-xxxhdpi | 192×192 |

### 5.2 Reserved Names — DO NOT USE as resource IDs

Colors: `background`, `foreground`, `white`, `black`  
Icons: `icon`, `logo`, `image`  
Views: `view`, `text`, `button`, `layout`

```xml
<!-- ❌ --><color name="background">#FFF</color>
<!-- ✅ --><color name="app_background">#FFF</color>
```

## 6. Build Error Quick Reference

| Error Keyword | Cause | Fix |
|---------------|-------|-----|
| `Unresolved reference` | Missing import/dep | Check imports, verify deps |
| `Type mismatch` | Type incompatibility | Check param types |
| `@Composable invocations` | Wrong context | Ensure caller is @Composable |
| `Duplicate class` | Dep conflict | `./gradlew dependencies` |
| `AAPT: error` | Resource XML error | Check XML syntax |

```bash
./gradlew clean assembleDebug
./gradlew :app:dependencies
./gradlew assembleDebug --stacktrace
```

## 7. Material Design 3

### Touch Targets
- Minimum: **48×48dp**
- Primary actions: **56×56dp**
- Spacing between targets: **8dp minimum**

### Color Contrast
- Body text: **4.5:1**
- Large text (18sp+): **3:1**
- UI components: **3:1**

### 8dp Grid

| Token | Value |
|-------|-------|
| xs | 4dp |
| sm | 8dp |
| md | 16dp |
| lg | 24dp |
| xl | 32dp |

### Typography Scale

| Category | Sizes |
|----------|-------|
| Display | 57sp, 45sp, 36sp |
| Headline | 32sp, 28sp, 24sp |
| Title | 22sp, 16sp, 14sp |
| Body | 16sp, 14sp, 12sp |
| Label | 14sp, 12sp, 11sp |

### Anti-Patterns
- Touch targets < 48dp
- Non-8dp spacing multiples
- Missing dark theme
- Missing `contentDescription` on interactive elements
- Startup > 2s without progress indicator

## 8. Accessibility

- All interactive elements must have `contentDescription`
- Don't include element type in labels ("Save" not "Save button")
- Support TalkBack navigation order
- Minimum contrast 4.5:1 for text

## 9. Performance

- Crash rate threshold (Google Play): **< 1.09%**
- ANR rate threshold: **< 0.47%**
- Target 60 FPS (< 16ms/frame)
- Avoid creating objects inside Composables (recreated on every recomposition)
