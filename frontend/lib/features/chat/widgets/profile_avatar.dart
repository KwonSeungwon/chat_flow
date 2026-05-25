import 'package:flutter/material.dart';

/// Reusable profile avatar widget used by both [showProfileDialog] and [ChatPage].
class ProfileAvatar extends StatelessWidget {
  final String? url;
  final double radius;
  const ProfileAvatar({super.key, required this.url, required this.radius});

  @override
  Widget build(BuildContext context) {
    return CircleAvatar(
      radius: radius,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
      child: ClipOval(
        child: (url != null && url!.isNotEmpty)
            ? Image.network(
                url!,
                width: radius * 2,
                height: radius * 2,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    Icon(Icons.person, size: radius),
              )
            : Icon(Icons.person, size: radius),
      ),
    );
  }
}
