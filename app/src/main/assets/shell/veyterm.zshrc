# VeyTerm's offline shell defaults. Keep this file dependency-free: no network
# calls, plugin downloads, or package-manager commands run during shell startup.
autoload -Uz colors 2>/dev/null && colors
setopt autocd interactivecomments

if [[ -n "$TERM" && "$TERM" != "dumb" ]]; then
  PROMPT='%F{cyan}%n@%m%f:%F{blue}%~%f %# '
else
  PROMPT='%n@%m:%~ %# '
fi

export EDITOR="${EDITOR:-vi}"
export VISUAL="${VISUAL:-$EDITOR}"
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
