//  backlift-development-reloader.js
//  (c) 2012 Cole Krumbholz, SendSpree Inc.
//
//  This document may be used and distributed in accordance with 
//  the MIT license. You may obtain a copy of the license at 
//    http://www.opensource.org/licenses/mit-license.php
// 
//  Requres backlift-pusher-helpers.js
//  This helper js file is for development use only. It should be 
//  deleted from the project before deploying to production.


// Enable pusher logging
Backlift.Pusher.log = function(message) {
	if (window.console && window.console.log) window.console.log(message);
};

// Reload page when updated
Backlift.pusherInit('development', '25cdf614bbf9a547045b');
Backlift.pusherOn('development', 'updated', function(data) {
	window.location.reload(true);
});
