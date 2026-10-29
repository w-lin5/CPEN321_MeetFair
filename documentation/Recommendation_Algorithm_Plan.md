
# MeetFair Recommendation Algorithm — Mathematical Formulas

## 1. Summary of Core Formulas

### 1.1 Fair Credit Balance (Already Implemented)

$$
\begin{aligned}
\Delta FC_i &= B_i^{actual}-\overline{B}^{actual}\\
FC_i^{new} &= FC_i^{old}+\Delta FC_i
\end{aligned}
$$

Members who have historically carried a greater travel burden than the group average earn positive Fair Credit.

This calculation is already implemented in the existing settlement logic. The recommendation algorithm only reads the current Fair Credit balance and does not modify it.

### 1.2 Recommendation Weight (Reuse Existing Function)

$$
\boxed{
w_i=\mathrm{clamp}\left(1+0.1\frac{FC_i}{3600},1,1.5\right)
}
$$

This formula converts a member's historical Fair Credit balance into a recommendation weight.

- **Zero or negative Fair Credit:** The member receives a weight of 1.
- **Positive Fair Credit:** The member receives a higher weight, up to a maximum of 1.5.

This ensures that members who have previously experienced greater travel burdens receive higher priority during location recommendations.

### 1.3 Normalized Travel Burden (Reuse Existing Function)

$$
\boxed{
B_{ij}=\frac{ETA_{ij}\times M_i}{60}
}
$$

Where:

- $ETA_{ij}$: Estimated travel time, in seconds, for member $i$ to reach candidate location $j$.
- $M_i$: Travel burden multiplier associated with member $i$'s transportation mode.
- $B_{ij}$: Normalized travel burden, measured in minutes.

This allows the recommendation algorithm to account for differences between transportation modes rather than relying solely on raw travel time.

### 1.4 Weighted Travel Cost (New Implementation)

$$
\boxed{
W_j=\frac{\sum_{i=1}^{n}w_i B_{ij}}{\sum_{i=1}^{n}w_i}
}
$$

This formula calculates the Fair Credit–weighted average travel burden for candidate location $j$.

Members with higher Fair Credit receive greater weight in the calculation, meaning their travel convenience has a stronger influence on the recommendation score.

### 1.5 Maximum Travel Burden (New Implementation)

$$
\boxed{
M_j^{max}=\max_i B_{ij}
}
$$

This measures the highest individual travel burden among all participating members for candidate location $j$.

It prevents the algorithm from focusing exclusively on average travel efficiency while overlooking members who would face exceptionally long commutes.

### 1.6 Travel Imbalance (New Implementation)

$$
\boxed{
I_j=\frac{1}{n}\sum_{i=1}^{n}|B_{ij}-\overline{B}_j|
}
$$

Where:

$$
\overline{B}_j=\frac{1}{n}\sum_{i=1}^{n}B_{ij}
$$

This formula uses the Mean Absolute Deviation (MAD) to measure differences in travel burden among group members.

A lower value indicates that members' travel burdens are more evenly distributed, representing a more balanced meetup location.

---

## 2. Final Recommendation Score

The recommendation algorithm combines three components:

$$
\boxed{
Score_j=W_j+\beta M_j^{max}+\gamma I_j
}
$$

The complete formula is:

$$
\boxed{
Score_j=
\frac{\sum_i w_iB_{ij}}{\sum_i w_i}
+\beta\max_i B_{ij}
+\gamma\frac{1}{n}\sum_i|B_{ij}-\overline{B}_j|
}
$$

Each component serves a different purpose:

| Component | Purpose |
|---|---|
| $W_j$ | Measures average travel burden while incorporating historical Fair Credit. |
| $\beta M_j^{max}$ | Penalizes candidate locations that require an individual member to travel exceptionally far. |
| $\gamma I_j$ | Penalizes unequal travel burdens among participating members. |
| $\beta,\gamma$ | Non-negative parameters that determine the relative importance of maximum travel burden and travel imbalance. These must be calibrated through testing. |

**A lower recommendation score indicates a more desirable candidate location.**

By combining these factors, the algorithm aims to balance overall travel efficiency, historical fairness, and the distribution of travel burdens across the group.

---

## 3. Phase 2 — Incorporating Personal Preferences

The next phase extends the recommendation algorithm to consider members' personal location preferences.

The objective is to give members with higher Fair Credit greater influence not only over travel convenience but also over how well recommended locations match their preferences.

### 3.1 Preference Mismatch

$$
\boxed{
P_{ij}\in[0,1]
}
$$

Where:

- $P_{ij}=0$: Candidate location $j$ fully matches member $i$'s preferences.
- $P_{ij}=1$: Candidate location $j$ does not match member $i$'s preferences.

The initial implementation will use the four preference categories currently supported by MeetFair:

`COFFEE / FOOD / OUTDOORS / SOCIAL`

A mapping between Google Places location types and these four categories must be established to calculate preference mismatch scores.

### 3.2 Fair Credit–Weighted Preference Penalty

$$
\boxed{
P_j^{weighted}=
\mu\frac{\sum_iw_iP_{ij}}{\sum_iw_i}
}
$$

Where:

- $P_{ij}$: Preference mismatch between member $i$ and candidate location $j$.
- $w_i$: Member $i$'s recommendation weight derived from Fair Credit.
- $\mu$: A parameter that converts preference mismatch into an equivalent travel-time penalty, measured in minutes.

This approach ensures that the preferences of members with higher Fair Credit have a greater influence on the overall recommendation score.

### 3.3 Extended Final Recommendation Score

After incorporating personal preferences, the final formula becomes:

$$
\boxed{
Score_j=
W_j+\beta M_j^{max}+\gamma I_j+P_j^{weighted}
}
$$

Or, in its fully expanded form:

$$
\boxed{
Score_j=
\frac{\sum_iw_iB_{ij}}{\sum_iw_i}
+\beta\max_i B_{ij}
+\gamma\frac{1}{n}\sum_i|B_{ij}-\overline{B}_j|
+\mu\frac{\sum_iw_iP_{ij}}{\sum_iw_i}
}
$$

In this extended model, Fair Credit influences two aspects of the recommendation process:

- **Travel Convenience:** Members with higher Fair Credit have a greater influence on the weighted travel cost.
- **Personal Preferences:** Members with higher Fair Credit have a greater influence on the weighted preference penalty.

This ensures that the recommendation algorithm does not simply prioritize shorter commutes for members with higher Fair Credit, but also gives greater consideration to their preferred types of meetup locations.

**Overall, the objective is to recommend locations that balance travel efficiency, individual preferences, and long-term fairness across multiple meetups.**
