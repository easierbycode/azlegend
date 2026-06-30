_.mixin

  capitalize: (string) ->
    string.charAt(0).toUpperCase() + string.substring(1).toLowerCase()

  titleize: (string) ->
    string
      .replace(/_/g, ' ')
      .replace('.mp3', '')
      .split(' ').map((word) -> _(word).capitalize()).join(' ')