# iOS public-content safety rollout

Community, convoy, DM and event publishing validators now reject a bounded set
of severe harmful phrases. Unicode normalization is only a detection probe;
valid stored text is never rewritten. Driver's native read projection applies
the same baseline without writing or deleting Driver-owned content. This is not
exhaustive language/image moderation. Human moderation is still required.

Driver posts/comments expose private selected-content reporting plus navigation
to the real author profile/block flow. Sponsored map advertisements expose a
private report action. The server binds the actor UID, validates the surface and
IDs, checks current product/publication authority, and captures the source item
itself. It never accepts client text as authoritative evidence. Reports use the
existing private moderationReports queue and rate cap, deduplicate, and cannot
reopen reviewed reports. A private owner document write serializes concurrent
new reports by one actor. Normal report evidence is never censored by the text
filter. No message history or unrelated private content is copied.

Operator duties before App Store review: assign staff and a response deadline,
verify the private queue shows new surfaces and snapshots, test blocking and
reporting with two accounts, and verify the existing advertiser-management and
Driver moderation procedures can remove/limit reported material promptly. Review
uploaded images and external Driver content using those procedures. No paid
AI/image-scanning service is provisioned or represented as implemented.

Deploy backend support before distributing the new client. Merging this PR starts
potentially billable Functions deployment, so explicit owner rollout/cost approval
is required. No production call, report, block or deploy is performed by tests.
The text filter can reject quoted harmful phrases; reports remain the route for
preserving such evidence privately. Test Swedish/English and accessibility text
on the signed app; the ad popup retains a reachable Close and bounded scroller.
