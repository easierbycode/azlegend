class @Photos extends Backbone.Collection

  url: 'https://graph.facebook.com/1180218081500/photos?access_token=CAACBHbEiON8BAA2hDO7ZAxIcIbsZC9USiz3yz7fKtupLRwtRtn7gPVGiwpjaOcsZCREztzD3CThBXKaUrv1yKzMwB14bbEDwHwbB5e7gGRRdqh4BkugbDYlZBZCg91BPmEzOBLQsZBx1RjdarV6193'

  parse: (response) ->
    response.data


class @PhotosView extends Backbone.View

  initialize: ->
    @collection = new Photos
    @collection.fetch()

    @collection.on 'reset', @render, @

  render: ->
    html = JST.photos
      collection: @collection

    @$el.html html