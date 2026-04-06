import 'package:flutter/material.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/patient_card.dart';

class PatientCardWidget extends StatelessWidget {
  final PatientCard card;
  final bool isMine;

  const PatientCardWidget({
    super.key,
    required this.card,
    required this.isMine,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Container(
      constraints: const BoxConstraints(maxWidth: 320),
      decoration: BoxDecoration(
        color: cs.surfaceContainer,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(20),
          topRight: const Radius.circular(20),
          bottomLeft: Radius.circular(isMine ? 20 : 5),
          bottomRight: Radius.circular(isMine ? 5 : 20),
        ),
        border: Border.all(color: AppColors.secondary.withAlpha(100), width: 1),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.secondary.withAlpha(30),
              borderRadius: const BorderRadius.vertical(top: Radius.circular(19)),
            ),
            child: Row(
              children: [
                const Icon(Icons.person_outline_rounded,
                    size: 16, color: AppColors.secondary),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    card.patientName,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: AppColors.secondary,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppColors.secondary.withAlpha(40),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    card.roomNumber,
                    style: const TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: AppColors.secondary,
                    ),
                  ),
                ),
              ],
            ),
          ),

          // Body
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _InfoRow(
                  icon: Icons.medical_information_outlined,
                  label: '진단',
                  value: card.diagnosis,
                ),
                if (card.allergies != null && card.allergies!.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  _InfoRow(
                    icon: Icons.warning_amber_rounded,
                    label: '알러지',
                    value: card.allergies!,
                    valueColor: AppColors.warning,
                  ),
                ],
              ],
            ),
          ),

          // Vitals
          if (_hasVitals) ...[
            Divider(
              height: 1,
              color: cs.outline.withAlpha(60),
              indent: 12,
              endIndent: 12,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
              child: Wrap(
                spacing: 8,
                runSpacing: 4,
                children: [
                  if (card.bloodPressure != null)
                    _VitalChip(label: 'BP', value: card.bloodPressure!),
                  if (card.heartRate != null)
                    _VitalChip(
                      label: 'HR',
                      value: '${card.heartRate}',
                      color: _heartRateColor(card.heartRate!),
                    ),
                  if (card.respiratoryRate != null)
                    _VitalChip(label: 'RR', value: '${card.respiratoryRate}'),
                  if (card.bodyTemperature != null)
                    _VitalChip(
                      label: 'BT',
                      value: '${card.bodyTemperature!.toStringAsFixed(1)}°C',
                      color: _tempColor(card.bodyTemperature!),
                    ),
                  if (card.spO2 != null)
                    _VitalChip(
                      label: 'SpO2',
                      value: '${card.spO2}%',
                      color: _spo2Color(card.spO2!),
                    ),
                ],
              ),
            ),
          ],

          // Notes footer
          if (card.notes != null && card.notes!.isNotEmpty) ...[
            Divider(
              height: 1,
              color: Theme.of(context).colorScheme.outline.withAlpha(60),
              indent: 12,
              endIndent: 12,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 6, 12, 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.notes_rounded,
                      size: 13,
                      color: Theme.of(context)
                          .colorScheme
                          .onSurfaceVariant
                          .withAlpha(160)),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      card.notes!,
                      style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context)
                            .colorScheme
                            .onSurfaceVariant
                            .withAlpha(180),
                        height: 1.4,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  bool get _hasVitals =>
      card.bloodPressure != null ||
      card.heartRate != null ||
      card.respiratoryRate != null ||
      card.bodyTemperature != null ||
      card.spO2 != null;

  Color _heartRateColor(int hr) {
    if (hr < 60 || hr > 100) return AppColors.warning;
    return AppColors.success;
  }

  Color _tempColor(double temp) {
    if (temp >= 38.0) return AppColors.error;
    if (temp < 36.0) return AppColors.warning;
    return AppColors.success;
  }

  Color _spo2Color(int spo2) {
    if (spo2 < 95) return AppColors.error;
    if (spo2 < 98) return AppColors.warning;
    return AppColors.success;
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final Color? valueColor;

  const _InfoRow({
    required this.icon,
    required this.label,
    required this.value,
    this.valueColor,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 13, color: cs.onSurfaceVariant.withAlpha(160)),
        const SizedBox(width: 4),
        Text(
          '$label: ',
          style: TextStyle(
            fontSize: 12,
            color: cs.onSurfaceVariant.withAlpha(160),
            fontWeight: FontWeight.w500,
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: TextStyle(
              fontSize: 12,
              color: valueColor ?? cs.onSurface,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }
}

class _VitalChip extends StatelessWidget {
  final String label;
  final String value;
  final Color? color;

  const _VitalChip({required this.label, required this.value, this.color});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final chipColor = color ?? cs.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: chipColor.withAlpha(20),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: chipColor.withAlpha(60), width: 0.8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            '$label ',
            style: TextStyle(
              fontSize: 10,
              color: cs.onSurfaceVariant.withAlpha(160),
              fontWeight: FontWeight.w500,
            ),
          ),
          Text(
            value,
            style: TextStyle(
              fontSize: 11,
              color: chipColor,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}
