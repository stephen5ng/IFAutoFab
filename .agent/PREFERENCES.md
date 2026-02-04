# Developer Preferences

## Git Commit Messages
- **Structure**: Use detailed, multi-line commit messages.
- **Header**: Use Conventional Commits format (e.g., `feat:`, `fix:`, `chore:`, `style:`, `refactor:`).
- **Body**: 
    - Explain the "Why" and "How", not just the "What".
    - Use bullet points for multiple changes.
    - Include details about UI constraints (especially Android Auto specific ones).
- **Examples**:
    ```text
    feat(car): implement one-step voice input
    
    - Created ListeningScreen to handle programmatic SpeechRecognizer triggering.
    - Added lifecycle observers to manage microphone resources.
    - Replaced SearchTemplate with MessageTemplate to bypass manual button clicks.
    ```

## UI & UX Principles
- **Android Auto**:
    - Newest turns must be at the top of lists to avoid scroll reset issues.
    - Action Strips limited to 2 items max.
    - All list rows must have non-empty titles.
- **Shortcuts**: Prioritize "Look" over "Undo" for immediate context.
