# VeyTerm's offline shell defaults. Keep this file dependency-free: no network
# calls, plugin downloads, or package-manager commands run during shell startup.
case $- in
  *i*) ;;
  *) return ;;
esac

if [ -n "$TERM" ] && [ "$TERM" != dumb ]; then
  PS1='\[\e[36m\]\u@\h\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \$ '
else
  PS1='\u@\h:\w \$ '
fi

export EDITOR="${EDITOR:-vi}"
export VISUAL="${VISUAL:-$EDITOR}"
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias archlinux='veyterm-arch'
