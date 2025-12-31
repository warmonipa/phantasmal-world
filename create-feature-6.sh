#!/bin/bash
# Feature #6: General Improvements

set -e

echo "Creating Feature #6 branch: feature/general-improvements"

# Create new branch from master
git checkout master
git checkout -b feature/general-improvements

# Extract files from release/1.0.0
echo "Extracting keyboard compatibility improvements..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/application/Application.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/core/stores/UiStore.kt

echo "Extracting UI layer fix..."
git checkout release/1.0.0 -- \
  webui/src/jsMain/kotlin/world/phantasmal/webui/widgets/Menu.kt

# View status
git status

echo ""
echo "✅ Feature #6 files extracted successfully (3 files)"
echo ""
echo "⚠️  Dependency notice:"
echo "   This feature is independent of other features and can be submitted at any time"
echo "   Recommended to submit as the last PR after all other features"
echo ""
echo "Next steps:"
echo "1. Review changes: git diff --staged"
echo "2. Commit changes: git commit -m 'feat: general improvements"
echo ""
echo "Keyboard compatibility:"
echo "- Support macOS Cmd key (metaKey) as Ctrl modifier"
echo "- Apply to global keybindings (undo/redo, shortcuts)"
echo "- Improve cross-platform user experience"
echo ""
echo "UI fixes:"
echo "- Increase Menu z-index from 1000 to 1001"
echo "- Fix menu overlay issues with other UI components"
echo ""
echo "Files changed:"
echo "- Application.kt: Add metaKey support for preventDefault"
echo "- UiStore.kt: Map metaKey to Ctrl in keybinding dispatcher"
echo "- Menu.kt: Increase z-index to fix layering issues'"
echo "3. Push branch: git push -u origin feature/general-improvements"