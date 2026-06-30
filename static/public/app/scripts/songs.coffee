class @Songs extends Backbone.Collection

  initialize: (models, options = {}) ->
    @album = options.album or 'az_legend_demo'
    @title = _(@album).titleize()

  url: -> "/public/music/#{@album}.json"

  parse: (songs) ->
    _.each songs, (song) ->
      song.title = _(song.file).titleize()

    songs


@albumsLoaded = 0

class @SongsView extends Backbone.View

  initialize: (options) ->
    @collection = new Songs [], options
    @collection.fetch()

    @collection.on 'reset', @render, @
    @collection.on 'reset', ->
      albumsLoaded += 1

  render: ->
    tape = JST.tape title: @collection.title

    html = JST.songs
      collection: @collection
      tape: tape

    @$el.append html

    @$el.find('a').click (e) =>
      $('.tape').removeClass 'playing'
      isPlaying = /sm2_playing/g.test e.target.className
      tape = @$el.find('.tape')

      tape.toggleClass 'playing', !isPlaying
      true


#    album = @options.album
#    setTimeout ->
#      new iScroll(album)
#    , 200

    @


@loadAlbums = ->
  @albums = [
    'az_legend_demo'
#    'az_is_the_new_a_town'
#    'misc_tracks'
  ]

  @checkAlbumsLoaded = ->
    if albumsLoaded == albums.length
      setTimeout ->
        new InlinePlayer()
      , 200
    else
      setTimeout ->
        checkAlbumsLoaded()
      , 200

  @checkAlbumsLoaded()

  _.each albums, (album, n) ->
    new SongsView
      el: "#album#{n+1}"
      album: album