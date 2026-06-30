$ ->
  $.backstretch 'https://sphotos-a.xx.fbcdn.net/hphotos-prn1/s720x720/530949_2553598655156_1605432168_n.jpg'

  setTimeout ->
    initScroll()
  , 2000

  new PhotosView
    el: '#photos'

  loadAlbums()