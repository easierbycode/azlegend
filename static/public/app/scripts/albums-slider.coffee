@initScroll = ->
    @albumScroll = new iScroll 'albums-wrpr',
      maxScrollX: -1020
      snap: true
      momentum: false
      hScrollbar: false
      onScrollEnd: ->
        $('#indicator > li.active').removeClass 'active'
        $('#indicator > li').eq(@currPageX).addClass 'active'